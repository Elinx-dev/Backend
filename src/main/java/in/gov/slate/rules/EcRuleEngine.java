package in.gov.slate.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.config.ConfigService;
import in.gov.slate.connectors.EcConnector;
import in.gov.slate.connectors.model.EcModels.EcCertificate;
import in.gov.slate.connectors.model.EcModels.EcEntry;
import in.gov.slate.connectors.model.EcModels.EcRequest;
import in.gov.slate.transaction.TransactionContext;

/**
 * Encumbrance Certificate engine. It reports what the EC shows; it never
 * approves or blocks. A mortgage is only treated as discharged when a receipt
 * entry explicitly references the mortgage document.
 */
@Component
public class EcRuleEngine implements RuleEngine {

    private final EcConnector connector;
    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService config;
    private final ObjectMapper mapper;

    public EcRuleEngine(EcConnector connector, NamedParameterJdbcTemplate jdbc, ConfigService config,
                        ObjectMapper mapper) {
        this.connector = connector;
        this.jdbc = jdbc;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public String engine() {
        return "EC";
    }

    @Override
    public Outcome run(TransactionContext ctx, long requestId, LocalDate assessmentDate, String mode) {
        Map<String, Object> engineConfig = config.ruleEngineConfig(
                (String) ctx.transaction().get("state_code"), "EC");
        int lookbackYears = engineConfig.get("ec_lookback_years") == null
                ? 30 : ((Number) engineConfig.get("ec_lookback_years")).intValue();
        LocalDate from = assessmentDate.minusYears(lookbackYears);

        String village = (String) ctx.property().get("village_code");
        String surveyNo = (String) ctx.property().get("survey_no");
        String subdivisionNo = (String) ctx.property().get("subdivision_no");

        EcCertificate certificate = connector.fetch(new EcRequest(village, surveyNo, subdivisionNo, from, assessmentDate));
        if (certificate == null || !"AVAILABLE".equals(certificate.responseStatus())) {
            return new Outcome("NOT_CHECKED", "EC_DATA_UNAVAILABLE", Map.of(
                    "searchedFrom", from.toString(),
                    "searchedTo", assessmentDate.toString(),
                    "village", village, "surveyNo", surveyNo, "subdivisionNo", String.valueOf(subdivisionNo),
                    "note", "The EC source returned no usable certificate; this is not a discrepancy"));
        }

        long certificateId = persistCertificate(requestId, certificate, village, surveyNo, from, assessmentDate);

        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> mortgages = new ArrayList<>();
        boolean courtOrder = false;
        boolean unclassified = false;
        boolean ambiguousSchedule = false;

        List<EcEntry> entries = certificate.entries() == null ? List.of() : certificate.entries();
        for (EcEntry entry : entries) {
            String classified = classify(entry.nature());
            String matchStatus = matchSchedule(ctx, entry, surveyNo, subdivisionNo);
            persistEntry(certificateId, entry, classified, matchStatus);
            if ("NOT_MATCHED".equals(matchStatus)) {
                continue;
            }
            if ("AMBIGUOUS".equals(matchStatus)) {
                ambiguousSchedule = true;
            }
            switch (classified) {
                case "MORTGAGE_CREATE" -> mortgages.add(mortgageOf(entry, entries, assessmentDate));
                case "COURT_ORDER" -> {
                    courtOrder = true;
                    findings.add(finding("COURT_ORDER_PRESENT", entry.documentNo(),
                            "Court order or attachment on record: " + entry.nature()));
                }
                case "UNCLASSIFIED_ENTRY" -> {
                    unclassified = true;
                    findings.add(finding("UNCLASSIFIED_ENTRY", entry.documentNo(),
                            "Entry could not be classified: " + entry.nature()));
                }
                default -> {
                }
            }
        }

        mortgages.forEach(m -> persistMortgage(certificateId, m));
        boolean openMortgage = mortgages.stream().anyMatch(m -> "OPEN".equals(m.get("status")));
        mortgages.stream().filter(m -> "OPEN".equals(m.get("status"))).forEach(m ->
                findings.add(finding("OPEN_MORTGAGE", (String) m.get("documentId"),
                        "Mortgage is on record with no receipt referencing it")));

        boolean partialCoverage = "PARTIAL".equalsIgnoreCase(certificate.coverageStatus());
        if (partialCoverage) {
            findings.add(finding("PARTIAL_COVERAGE", certificate.certificateNo(),
                    certificate.coverageNote() == null
                            ? "The certificate does not cover the full requested period"
                            : certificate.coverageNote()));
        }

        String outcome;
        String reason;
        if (openMortgage) {
            outcome = "DISCREPANCY_DETECTED";
            reason = "OPEN_MORTGAGE";
        } else if (courtOrder) {
            outcome = "REVIEW_REQUIRED";
            reason = "COURT_ORDER_PRESENT";
        } else if (unclassified || ambiguousSchedule) {
            outcome = "REVIEW_REQUIRED";
            reason = unclassified ? "UNCLASSIFIED_ENTRY" : "AMBIGUOUS_SCHEDULE";
        } else if (partialCoverage) {
            // Partial coverage can never be reported as clean.
            outcome = "REVIEW_REQUIRED";
            reason = "PARTIAL_COVERAGE";
        } else {
            outcome = "NO_DISCREPANCY_DETECTED";
            reason = "NO_ENCUMBRANCE_FOUND_IN_AVAILABLE_PERIOD";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("certificateNo", certificate.certificateNo());
        payload.put("searchedFrom", from.toString());
        payload.put("searchedTo", assessmentDate.toString());
        payload.put("dataAvailableFrom", String.valueOf(certificate.periodFrom()));
        payload.put("dataAvailableTo", String.valueOf(certificate.periodTo()));
        payload.put("coverageStatus", certificate.coverageStatus());
        payload.put("entryCount", entries.size());
        payload.put("mortgages", mortgages);
        payload.put("findings", findings);
        payload.put("advisory", true);
        return new Outcome(outcome, reason, payload);
    }

    static String classify(String nature) {
        String n = nature == null ? "" : nature.toLowerCase(Locale.ROOT);
        if (n.contains("receipt") || n.contains("discharge") || n.contains("release of mortgage")
                || n.contains("cancellation of mortgage")) {
            return "RECEIPT";
        }
        if (n.contains("mortgage") || n.contains("deposit of title deeds") || n.contains("hypothec")) {
            return "MORTGAGE_CREATE";
        }
        if (n.contains("court") || n.contains("attachment") || n.contains("decree") || n.contains("injunction")
                || n.contains("order")) {
            return "COURT_ORDER";
        }
        if (n.contains("sale") || n.contains("gift") || n.contains("settlement") || n.contains("partition")
                || n.contains("release") || n.contains("lease") || n.contains("power of attorney")) {
            return "NON_ENCUMBRANCE_EVENT";
        }
        return "UNCLASSIFIED_ENTRY";
    }

    private String matchSchedule(TransactionContext ctx, EcEntry entry, String surveyNo, String subdivisionNo) {
        if (entry.schedule() == null) {
            return "AMBIGUOUS";
        }
        String entrySurvey = entry.schedule().surveyNo();
        String entrySub = entry.schedule().subdivisionNo();
        if (surveyNo.equals(entrySurvey)) {
            if (subdivisionNo == null || entrySub == null) {
                return subdivisionNo == null && entrySub == null ? "MATCH" : "AMBIGUOUS";
            }
            return subdivisionNo.equals(entrySub) ? "MATCH" : "NOT_MATCHED";
        }
        return lineageLinks(ctx, entrySurvey, entrySub, surveyNo, subdivisionNo) ? "HISTORICAL_MATCH" : "NOT_MATCHED";
    }

    /** Only authoritative, verified lineage rows may link an old survey number to the current one. */
    private boolean lineageLinks(TransactionContext ctx, String fromSurvey, String fromSub,
                                 String toSurvey, String toSub) {
        if (fromSurvey == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM rules.survey_lineage
                 WHERE state_code = :stateCode AND revenue_village = :village
                   AND from_survey_no = :fromSurvey
                   AND (from_subdivision IS NOT DISTINCT FROM :fromSub OR :fromSub IS NULL)
                   AND to_survey_no = :toSurvey
                   AND (to_subdivision IS NOT DISTINCT FROM :toSub OR :toSub IS NULL)
                   AND verified_at IS NOT NULL
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.property().get("state_code"))
                .addValue("village", ctx.property().get("village_code"))
                .addValue("fromSurvey", fromSurvey)
                .addValue("fromSub", fromSub)
                .addValue("toSurvey", toSurvey)
                .addValue("toSub", toSub), Integer.class);
        return count != null && count > 0;
    }

    private Map<String, Object> mortgageOf(EcEntry mortgage, List<EcEntry> entries, LocalDate assessmentDate) {
        EcEntry discharge = entries.stream()
                .filter(e -> "RECEIPT".equals(classify(e.nature())))
                .filter(e -> mortgage.documentNo() != null
                        && mortgage.documentNo().equals(e.previousDocumentReference()))
                .findFirst().orElse(null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("documentId", mortgage.documentNo());
        row.put("registrationDate", String.valueOf(mortgage.entryDate()));
        row.put("mortgagor", mortgage.executants() == null ? null : String.join(", ", mortgage.executants()));
        row.put("mortgagee", mortgage.lender() != null ? mortgage.lender()
                : (mortgage.claimants() == null ? null : String.join(", ", mortgage.claimants())));
        row.put("status", discharge == null ? "OPEN" : "DISCHARGED");
        row.put("releaseDocumentId", discharge == null ? null : discharge.documentNo());
        row.put("releaseDate", discharge == null ? null : String.valueOf(discharge.entryDate()));
        row.put("activeOnAssessment", discharge == null
                || (discharge.entryDate() != null && discharge.entryDate().isAfter(assessmentDate)));
        return row;
    }

    private long persistCertificate(long requestId, EcCertificate certificate, String village, String surveyNo,
                                    LocalDate from, LocalDate to) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO rules.ec_certificate (request_id, certificate_no, issued_date, village_name,
                    survey_numbers_searched, requested_from, requested_to, data_available_from, data_available_to,
                    coverage_status, raw_payload)
                VALUES (:requestId, :certificateNo, :issuedOn, :village,
                    string_to_array(:surveyNo, ','), :from, :to, :availableFrom, :availableTo,
                    :coverage, cast(:raw AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("certificateNo", certificate.certificateNo())
                .addValue("issuedOn", certificate.issuedOn())
                .addValue("village", village)
                .addValue("surveyNo", surveyNo)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("availableFrom", certificate.periodFrom())
                .addValue("availableTo", certificate.periodTo())
                .addValue("coverage", "PARTIAL".equalsIgnoreCase(certificate.coverageStatus())
                        ? "PARTIAL_COVERAGE" : "COMPLETE_AVAILABLE_COVERAGE")
                .addValue("raw", json(certificate)), keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    private void persistEntry(long certificateId, EcEntry entry, String classified, String matchStatus) {
        int year = entry.entryDate() != null ? entry.entryDate().getYear() : 0;
        String docNo = entry.documentNo() == null ? "UNKNOWN" : entry.documentNo();
        jdbc.update("""
                INSERT INTO rules.ec_entry (certificate_id, document_id, doc_no, doc_year, registration_date,
                    nature, classified_type, match_status, executants, claimants, consideration_value,
                    previous_doc_refs, remarks, schedules)
                VALUES (:certificateId, :documentId, :docNo, :docYear, :registrationDate,
                    :nature, :classified, :matchStatus, :executants, :claimants, :amount,
                    cast(:previousRefs AS jsonb), :remarks, cast(:schedules AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("certificateId", certificateId)
                .addValue("documentId", docNo)
                .addValue("docNo", docNo.contains("/") ? docNo.substring(0, docNo.indexOf('/')) : docNo)
                .addValue("docYear", year)
                .addValue("registrationDate", entry.entryDate())
                .addValue("nature", entry.nature())
                .addValue("classified", classified)
                .addValue("matchStatus", matchStatus)
                .addValue("executants", entry.executants() == null ? null : entry.executants().toArray(String[]::new))
                .addValue("claimants", entry.claimants() == null ? null : entry.claimants().toArray(String[]::new))
                .addValue("amount", entry.amount())
                .addValue("previousRefs", json(entry.previousDocumentReference() == null
                        ? List.of() : List.of(entry.previousDocumentReference())))
                .addValue("remarks", entry.remarks())
                .addValue("schedules", json(entry.schedule())));
    }

    private void persistMortgage(long certificateId, Map<String, Object> mortgage) {
        jdbc.update("""
                INSERT INTO rules.ec_mortgage_record (certificate_id, document_id, registration_date, mortgagor,
                    mortgagee, property_schedule, status, release_document_id, release_date, active_on_assessment)
                VALUES (:certificateId, :documentId, cast(:registrationDate AS date), :mortgagor,
                    :mortgagee, NULL, :status, :releaseDoc, cast(:releaseDate AS date), :active)
                ON CONFLICT (certificate_id, document_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("certificateId", certificateId)
                .addValue("documentId", mortgage.get("documentId"))
                .addValue("registrationDate", nullableDate(mortgage.get("registrationDate")))
                .addValue("mortgagor", mortgage.get("mortgagor"))
                .addValue("mortgagee", mortgage.get("mortgagee"))
                .addValue("status", mortgage.get("status"))
                .addValue("releaseDoc", mortgage.get("releaseDocumentId"))
                .addValue("releaseDate", nullableDate(mortgage.get("releaseDate")))
                .addValue("active", mortgage.get("activeOnAssessment")));
    }

    private String nullableDate(Object value) {
        String s = value == null ? null : value.toString();
        return (s == null || s.isBlank() || "null".equals(s)) ? null : s;
    }

    private Map<String, Object> finding(String code, String reference, String message) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("code", code);
        finding.put("reference", reference);
        finding.put("message", message);
        return finding;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise EC payload", e);
        }
    }
}
