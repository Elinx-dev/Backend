package in.gov.slate.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import in.gov.slate.connectors.model.EcModels.EcSchedule;
import in.gov.slate.connectors.model.EcModels.EcSurveyLink;
import in.gov.slate.transaction.TransactionContext;

/**
 * Encumbrance Certificate engine. Looks the property up by district, taluk,
 * revenue village, survey and subdivision over the configured look-back period,
 * keeps only EC entries that belong to the property, and reports open mortgages
 * and court matters. A different survey/subdivision number is only treated as
 * the same property when an authoritative source links them; related numbers
 * without such a link are flagged for review, never assumed.
 */
@Component
public class EcRuleEngine implements RuleEngine {

    private static final Pattern DOC_REF = Pattern.compile("(\\d+)\\s*/\\s*(\\d{4})");
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

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
    public Map<String, Object> requestPayload(TransactionContext ctx, LocalDate assessmentDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engine", engine());
        payload.put("propertyRef", ctx.propertyRef());
        payload.putAll(searchedWith(buildRequest(ctx, assessmentDate), lookbackYears(ctx)));
        return payload;
    }

    @Override
    public Outcome run(TransactionContext ctx, long requestId, LocalDate assessmentDate, String mode) {
        Integer lookbackYears = lookbackYears(ctx);
        EcRequest request = buildRequest(ctx, assessmentDate);
        Map<String, Object> searched = searchedWith(request, lookbackYears);

        if (request.searchFrom() == null) {
            return notChecked("EC_LOOKBACK_NOT_CONFIGURED", searched,
                    "ec_lookback_years is not configured in cfg.rule_engine_config for this state");
        }
        if (request.surveyNo() == null) {
            return notChecked("SURVEY_NO_MISSING", searched, "The property has no survey number to search with");
        }

        EcCertificate certificate = connector.fetch(request);
        if (certificate == null || !"AVAILABLE".equals(certificate.responseStatus())) {
            return notChecked("EC_DATA_UNAVAILABLE", searched,
                    "The EC source returned no usable certificate; this is not a discrepancy");
        }

        long certificateId = persistCertificate(requestId, certificate, request);
        Subject subject = new Subject(request.village(), request.surveyNo(), request.subdivisionNo());
        Evaluation evaluation = evaluate(subject, certificate,
                (fromSurvey, fromSub) -> authoritativeLink(ctx, request, certificate, fromSurvey, fromSub),
                new Keywords(config.ecClassificationKeywords()), assessmentDate);
        evaluation.entries().forEach(entry -> persistEntry(certificateId, entry));
        evaluation.mortgages().forEach(mortgage -> persistMortgage(certificateId, mortgage));

        Set<String> blockingReasons = config.blockingRuleReasons((String) ctx.transaction().get("state_code"))
                .getOrDefault(engine(), Set.of());
        boolean blocking = blockingReasons.contains(evaluation.reason());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", blocking ? evaluation.summary() + " Pre-registration is stopped." : evaluation.summary());
        payload.put("blocking", blocking);
        payload.put("advisory", !blocking);
        payload.put("searchedWith", searched);
        payload.put("certificateNo", certificate.certificateNo());
        payload.put("dataAvailableFrom", String.valueOf(certificate.periodFrom()));
        payload.put("dataAvailableTo", String.valueOf(certificate.periodTo()));
        payload.put("coverageStatus", certificate.coverageStatus());
        payload.put("entryCount", evaluation.entries().size());
        payload.put("entries", evaluation.entries().stream().map(EcRuleEngine::entryView).toList());
        payload.put("mortgages", evaluation.mortgages());
        payload.put("findings", evaluation.findings());
        return new Outcome(evaluation.outcome(), evaluation.reason(), payload);
    }

    private Outcome notChecked(String reason, Map<String, Object> searched, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", note);
        payload.put("blocking", false);
        payload.put("advisory", true);
        payload.put("searchedWith", searched);
        return new Outcome("NOT_CHECKED", reason, payload);
    }

    private Integer lookbackYears(TransactionContext ctx) {
        Object years = config.ruleEngineConfig((String) ctx.transaction().get("state_code"), engine())
                .get("ec_lookback_years");
        return years instanceof Number n ? n.intValue() : null;
    }

    private EcRequest buildRequest(TransactionContext ctx, LocalDate assessmentDate) {
        Map<String, Object> property = ctx.property();
        Integer lookbackYears = lookbackYears(ctx);
        return new EcRequest(text(property.get("district_code")), text(property.get("taluk_code")),
                text(property.get("village_code")), text(property.get("survey_no")),
                text(property.get("subdivision_no")),
                lookbackYears == null ? null : assessmentDate.minusYears(lookbackYears), assessmentDate);
    }

    private static Map<String, Object> searchedWith(EcRequest request, Integer lookbackYears) {
        Map<String, Object> searched = new LinkedHashMap<>();
        searched.put("district", request.district());
        searched.put("taluk", request.taluk());
        searched.put("village", request.village());
        searched.put("surveyNo", request.surveyNo());
        searched.put("subdivisionNo", request.subdivisionNo());
        searched.put("lookbackYears", lookbackYears);
        searched.put("searchFrom", request.searchFrom() == null ? null : request.searchFrom().toString());
        searched.put("searchTo", request.searchTo() == null ? null : request.searchTo().toString());
        return searched;
    }

    // ---------------------------------------------------------------- evaluation

    record Subject(String village, String surveyNo, String subdivisionNo) {
    }

    /** Whether an authoritative source links an EC survey/subdivision to the property's current one. */
    @FunctionalInterface
    interface SurveyLinkage {
        boolean links(String fromSurveyNo, String fromSubdivisionNo);
    }

    /** DB-maintained keywords (cfg.ec_classification_keyword), lower-cased, grouped by category. */
    record Keywords(Map<String, List<String>> byCategory) {
        boolean matches(String category, String text) {
            if (text == null || text.isBlank()) {
                return false;
            }
            String haystack = text.toLowerCase(Locale.ROOT);
            return byCategory.getOrDefault(category, List.of()).stream().anyMatch(haystack::contains);
        }
    }

    /** Document number + year, e.g. 181/2016. */
    record DocRef(String number, Integer year) {
        static DocRef parse(String text, LocalDate fallbackDate) {
            if (text == null || text.isBlank()) {
                return null;
            }
            Matcher full = DOC_REF.matcher(text);
            if (full.find()) {
                return new DocRef(stripZeros(full.group(1)), Integer.parseInt(full.group(2)));
            }
            Matcher digits = DIGITS.matcher(text);
            if (digits.find()) {
                return new DocRef(stripZeros(digits.group(1)), fallbackDate == null ? null : fallbackDate.getYear());
            }
            return null;
        }

        static List<DocRef> parseAll(String text) {
            List<DocRef> refs = new ArrayList<>();
            if (text != null) {
                Matcher matcher = DOC_REF.matcher(text);
                while (matcher.find()) {
                    refs.add(new DocRef(stripZeros(matcher.group(1)), Integer.parseInt(matcher.group(2))));
                }
            }
            return refs;
        }

        boolean sameAs(DocRef other) {
            return other != null && year != null && number.equals(other.number) && year.equals(other.year);
        }

        @Override
        public String toString() {
            return year == null ? number : number + "/" + year;
        }

        private static String stripZeros(String number) {
            return number.replaceFirst("^0+(?=\\d)", "");
        }
    }

    record AssessedEntry(EcEntry entry, DocRef docRef, String classifiedType, String matchStatus) {
        String reference() {
            return docRef != null ? docRef.toString() : entry.documentNo();
        }
    }

    record Evaluation(String outcome, String reason, String summary, List<AssessedEntry> entries,
                      List<Map<String, Object>> mortgages, List<Map<String, Object>> findings) {
    }

    static Evaluation evaluate(Subject subject, EcCertificate certificate, SurveyLinkage linkage, Keywords keywords,
                               LocalDate assessmentDate) {
        List<EcEntry> raw = certificate.entries() == null ? List.of() : certificate.entries();
        List<AssessedEntry> assessed = new ArrayList<>();
        for (EcEntry entry : raw) {
            assessed.add(new AssessedEntry(entry, DocRef.parse(entry.documentNo(), entry.entryDate()),
                    classify(entry.nature(), entry.remarks(), keywords),
                    matchSchedule(subject, entry.schedule(), linkage)));
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> mortgages = new ArrayList<>();
        boolean attachment = false;
        boolean decree = false;
        boolean unclearCourt = false;
        boolean unlinked = false;
        boolean unclassified = false;

        for (AssessedEntry a : assessed) {
            String status = a.matchStatus();
            if ("NOT_MATCHED".equals(status)) {
                continue;
            }
            if ("LINK_UNCONFIRMED".equals(status) || "AMBIGUOUS".equals(status)) {
                unlinked = true;
                findings.add(finding("SURVEY_LINK_UNCONFIRMED", a.reference(), unlinkedMessage(subject, a)));
                continue;
            }
            switch (a.classifiedType()) {
                case "MORTGAGE_CREATE" -> mortgages.add(mortgageOf(a, assessed, assessmentDate));
                case "COURT_ATTACHMENT" -> {
                    attachment = true;
                    findings.add(finding("COURT_ATTACHMENT", a.reference(),
                            "Court attachment on record: " + a.entry().nature() + " - " + a.entry().remarks()));
                }
                case "COURT_DECREE" -> {
                    decree = true;
                    findings.add(finding("COURT_DECREE", a.reference(),
                            "Court decree / judgment on record; manual review required: " + a.entry().nature()));
                }
                case "COURT_UNCLEAR" -> {
                    unclearCourt = true;
                    findings.add(finding("COURT_ENTRY_UNCLEAR", a.reference(),
                            "Court-related entry that is not clearly an attachment or a decree; manual review required: "
                                    + a.entry().nature()));
                }
                case "UNCLASSIFIED_ENTRY" -> {
                    unclassified = true;
                    findings.add(finding("UNCLASSIFIED_ENTRY", a.reference(),
                            "Entry could not be classified: " + a.entry().nature()));
                }
                default -> {
                }
            }
        }

        boolean openMortgage = false;
        for (Map<String, Object> mortgage : mortgages) {
            if ("OPEN".equals(mortgage.get("status"))) {
                openMortgage = true;
                findings.add(finding("OPEN_MORTGAGE", (String) mortgage.get("documentId"),
                        "Mortgage " + mortgage.get("documentId") + " has no receipt referencing it"));
            }
        }

        String coverage = certificate.coverageStatus() == null ? "" : certificate.coverageStatus();
        boolean partialCoverage = "PARTIAL".equalsIgnoreCase(coverage) || "NONE".equalsIgnoreCase(coverage);
        if (partialCoverage) {
            findings.add(finding("PARTIAL_COVERAGE", certificate.certificateNo(),
                    certificate.coverageNote() == null
                            ? "The certificate does not cover the full requested period"
                            : certificate.coverageNote()));
        }

        String outcome;
        String reason;
        if (attachment) {
            outcome = "DISCREPANCY_DETECTED";
            reason = "COURT_ATTACHMENT";
        } else if (openMortgage) {
            outcome = "DISCREPANCY_DETECTED";
            reason = "OPEN_MORTGAGE";
        } else if (decree) {
            outcome = "REVIEW_REQUIRED";
            reason = "COURT_DECREE";
        } else if (unclearCourt) {
            outcome = "REVIEW_REQUIRED";
            reason = "COURT_ENTRY_UNCLEAR";
        } else if (unlinked) {
            outcome = "REVIEW_REQUIRED";
            reason = "SURVEY_LINK_UNCONFIRMED";
        } else if (unclassified) {
            outcome = "REVIEW_REQUIRED";
            reason = "UNCLASSIFIED_ENTRY";
        } else if (partialCoverage) {
            // Partial coverage can never be reported as clean.
            outcome = "REVIEW_REQUIRED";
            reason = "PARTIAL_COVERAGE";
        } else {
            outcome = "NO_DISCREPANCY_DETECTED";
            reason = "NO_ENCUMBRANCE_FOUND_IN_AVAILABLE_PERIOD";
        }

        String summary;
        if (!findings.isEmpty()) {
            summary = String.join("; ", findings.stream().map(f -> (String) f.get("message")).toList()) + ".";
        } else if (!mortgages.isEmpty()) {
            summary = mortgages.size() + " mortgage(s) on record, all discharged. No other encumbrance found.";
        } else {
            summary = "No encumbrance found for this property in the searched period.";
        }
        return new Evaluation(outcome, reason, summary, assessed, mortgages, findings);
    }

    /**
     * MATCH when survey + subdivision are the same; HISTORICAL_MATCH only when an
     * authoritative source links the EC number to the current one; LINK_UNCONFIRMED
     * when the numbers are related (same base survey) but unlinked; NOT_MATCHED
     * when the entry clearly belongs to another property.
     */
    static String matchSchedule(Subject subject, EcSchedule schedule, SurveyLinkage linkage) {
        if (schedule == null || blank(schedule.surveyNo())) {
            return "AMBIGUOUS";
        }
        if (!blank(schedule.village()) && !blank(subject.village())
                && !norm(schedule.village()).equals(norm(subject.village()))) {
            return "NOT_MATCHED";
        }
        if (parcelKey(schedule.surveyNo(), schedule.subdivisionNo())
                .equals(parcelKey(subject.surveyNo(), subject.subdivisionNo()))) {
            return "MATCH";
        }
        if (linkage.links(schedule.surveyNo(), schedule.subdivisionNo())) {
            return "HISTORICAL_MATCH";
        }
        if (baseSurvey(schedule.surveyNo()).equals(baseSurvey(subject.surveyNo()))) {
            return "LINK_UNCONFIRMED";
        }
        return "NOT_MATCHED";
    }

    /** Court attachment is decided from Nature and Remarks together, never from Nature alone. */
    static String classify(String nature, String remarks, Keywords keywords) {
        if (keywords.matches("RECEIPT", nature)) {
            return "RECEIPT";
        }
        if (keywords.matches("MORTGAGE", nature)) {
            return "MORTGAGE_CREATE";
        }
        if (keywords.matches("COURT", nature)) {
            if (keywords.matches("COURT_ATTACHMENT", remarks)
                    && !keywords.matches("COURT_ATTACHMENT_EXCLUDE", remarks)) {
                return "COURT_ATTACHMENT";
            }
            if (keywords.matches("COURT_DECREE", nature)) {
                return "COURT_DECREE";
            }
            return "COURT_UNCLEAR";
        }
        if (keywords.matches("NON_ENCUMBRANCE", nature)) {
            return "NON_ENCUMBRANCE_EVENT";
        }
        return "UNCLASSIFIED_ENTRY";
    }

    /** A mortgage is DISCHARGED only by a later Receipt whose previous document no. + year is the mortgage's. */
    static Map<String, Object> mortgageOf(AssessedEntry mortgage, List<AssessedEntry> entries,
                                          LocalDate assessmentDate) {
        AssessedEntry discharge = entries.stream()
                .filter(e -> e != mortgage && "RECEIPT".equals(e.classifiedType()))
                .filter(e -> mortgage.docRef() != null && DocRef.parseAll(e.entry().previousDocumentReference())
                        .stream().anyMatch(mortgage.docRef()::sameAs))
                .filter(e -> isLater(e, mortgage))
                .findFirst().orElse(null);
        EcEntry m = mortgage.entry();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("documentId", mortgage.reference());
        row.put("registrationDate", m.entryDate() == null ? null : m.entryDate().toString());
        row.put("mortgagor", m.executants() == null ? null : String.join(", ", m.executants()));
        row.put("mortgagee", m.lender() != null ? m.lender()
                : (m.claimants() == null ? null : String.join(", ", m.claimants())));
        row.put("status", discharge == null ? "OPEN" : "DISCHARGED");
        row.put("releaseDocumentId", discharge == null ? null : discharge.reference());
        row.put("releaseDate", discharge == null || discharge.entry().entryDate() == null
                ? null : discharge.entry().entryDate().toString());
        row.put("activeOnAssessment", discharge == null
                || (discharge.entry().entryDate() != null && discharge.entry().entryDate().isAfter(assessmentDate)));
        return row;
    }

    private static boolean isLater(AssessedEntry receipt, AssessedEntry mortgage) {
        LocalDate receiptDate = receipt.entry().entryDate();
        LocalDate mortgageDate = mortgage.entry().entryDate();
        if (receiptDate != null && mortgageDate != null) {
            return !receiptDate.isBefore(mortgageDate);
        }
        if (receipt.docRef() != null && receipt.docRef().year() != null && mortgage.docRef().year() != null) {
            return receipt.docRef().year() >= mortgage.docRef().year();
        }
        return true;
    }

    private static String unlinkedMessage(Subject subject, AssessedEntry a) {
        EcSchedule schedule = a.entry().schedule();
        if (schedule == null || blank(schedule.surveyNo())) {
            return "Entry " + a.reference() + " has no property schedule to match against; manual review required";
        }
        return "Entry " + a.reference() + " (" + a.entry().nature() + ") is recorded against survey "
                + parcelLabel(schedule.surveyNo(), schedule.subdivisionNo()) + " but this property is "
                + parcelLabel(subject.surveyNo(), subject.subdivisionNo())
                + "; no authoritative linkage confirms they are the same property";
    }

    private static Map<String, Object> entryView(AssessedEntry a) {
        EcEntry e = a.entry();
        EcSchedule s = e.schedule();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("documentNo", e.documentNo());
        view.put("documentYear", a.docRef() == null ? null : a.docRef().year());
        view.put("registrationDate", e.entryDate() == null ? null : e.entryDate().toString());
        view.put("nature", e.nature());
        view.put("executants", e.executants());
        view.put("claimants", e.claimants());
        view.put("previousDocumentReference", e.previousDocumentReference());
        view.put("remarks", e.remarks());
        view.put("surveyNo", s == null ? null : s.surveyNo());
        view.put("subdivisionNo", s == null ? null : s.subdivisionNo());
        view.put("extent", s == null ? null : s.extent());
        view.put("extentUnit", s == null ? null : s.extentUnit());
        view.put("boundaries", s == null ? null : s.boundaries());
        view.put("classifiedType", a.classifiedType());
        view.put("matchStatus", a.matchStatus());
        return view;
    }

    private static String parcelLabel(String surveyNo, String subdivisionNo) {
        return blank(subdivisionNo) ? surveyNo : surveyNo + "/" + subdivisionNo;
    }

    private static String parcelKey(String surveyNo, String subdivisionNo) {
        return blank(subdivisionNo) ? norm(surveyNo) : norm(surveyNo) + "/" + norm(subdivisionNo);
    }

    /** Leading numeric part of a survey number: 100A and 100/2 both have base 100. */
    private static String baseSurvey(String surveyNo) {
        Matcher digits = Pattern.compile("^(\\d+)").matcher(norm(surveyNo));
        return digits.find() ? stripLeadingZeros(digits.group(1)) : norm(surveyNo);
    }

    private static String stripLeadingZeros(String number) {
        return number.replaceFirst("^0+(?=\\d)", "");
    }

    private static String norm(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static Map<String, Object> finding(String code, String reference, String message) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("code", code);
        finding.put("reference", reference);
        finding.put("message", message);
        return finding;
    }

    // ---------------------------------------------------------------- linkage + persistence

    /** Linkage from the EC source response, or a verified, source-referenced row in rules.survey_lineage. */
    private boolean authoritativeLink(TransactionContext ctx, EcRequest request, EcCertificate certificate,
                                      String fromSurvey, String fromSub) {
        if (certificate.surveyLinks() != null) {
            boolean linked = certificate.surveyLinks().stream()
                    .filter(link -> !blank(link.sourceReference()))
                    .anyMatch(link -> sameParcel(link, fromSurvey, fromSub, request));
            if (linked) {
                return true;
            }
        }
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM rules.survey_lineage
                 WHERE state_code = :stateCode
                   AND district_code = :district AND taluk_code = :taluk
                   AND upper(revenue_village) = upper(:village)
                   AND upper(from_survey_no) = upper(:fromSurvey)
                   AND NULLIF(upper(trim(from_subdivision)), '')
                       IS NOT DISTINCT FROM NULLIF(upper(trim(CAST(:fromSub AS text))), '')
                   AND upper(to_survey_no) = upper(:toSurvey)
                   AND NULLIF(upper(trim(to_subdivision)), '')
                       IS NOT DISTINCT FROM NULLIF(upper(trim(CAST(:toSub AS text))), '')
                   AND verified_at IS NOT NULL
                   AND trim(source_reference) <> ''
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.property().get("state_code"))
                .addValue("district", request.district())
                .addValue("taluk", request.taluk())
                .addValue("village", request.village())
                .addValue("fromSurvey", fromSurvey)
                .addValue("fromSub", fromSub)
                .addValue("toSurvey", request.surveyNo())
                .addValue("toSub", request.subdivisionNo()), Integer.class);
        return count != null && count > 0;
    }

    private static boolean sameParcel(EcSurveyLink link, String fromSurvey, String fromSub, EcRequest request) {
        return parcelKey(link.fromSurveyNo(), link.fromSubdivisionNo()).equals(parcelKey(fromSurvey, fromSub))
                && parcelKey(link.toSurveyNo(), link.toSubdivisionNo())
                .equals(parcelKey(request.surveyNo(), request.subdivisionNo()));
    }

    private long persistCertificate(long requestId, EcCertificate certificate, EcRequest request) {
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
                .addValue("village", request.village())
                .addValue("surveyNo", request.surveyNo())
                .addValue("from", request.searchFrom())
                .addValue("to", request.searchTo())
                .addValue("availableFrom", certificate.periodFrom())
                .addValue("availableTo", certificate.periodTo())
                .addValue("coverage", "FULL".equalsIgnoreCase(certificate.coverageStatus())
                        ? "COMPLETE_AVAILABLE_COVERAGE" : "PARTIAL_COVERAGE")
                .addValue("raw", json(certificate)), keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    private void persistEntry(long certificateId, AssessedEntry assessed) {
        EcEntry entry = assessed.entry();
        String documentId = entry.documentNo() == null ? "UNKNOWN" : entry.documentNo();
        DocRef ref = assessed.docRef();
        jdbc.update("""
                INSERT INTO rules.ec_entry (certificate_id, document_id, doc_no, doc_year, registration_date,
                    nature, classified_type, match_status, executants, claimants, consideration_value,
                    previous_doc_refs, remarks, schedules)
                VALUES (:certificateId, :documentId, :docNo, :docYear, :registrationDate,
                    :nature, :classified, :matchStatus, :executants, :claimants, :amount,
                    cast(:previousRefs AS jsonb), :remarks, cast(:schedules AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("certificateId", certificateId)
                .addValue("documentId", documentId)
                .addValue("docNo", ref == null ? documentId : ref.number())
                .addValue("docYear", ref == null || ref.year() == null ? 0 : ref.year())
                .addValue("registrationDate", entry.entryDate())
                .addValue("nature", entry.nature())
                .addValue("classified", assessed.classifiedType())
                .addValue("matchStatus", assessed.matchStatus())
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
                .addValue("registrationDate", mortgage.get("registrationDate"))
                .addValue("mortgagor", mortgage.get("mortgagor"))
                .addValue("mortgagee", mortgage.get("mortgagee"))
                .addValue("status", mortgage.get("status"))
                .addValue("releaseDoc", mortgage.get("releaseDocumentId"))
                .addValue("releaseDate", mortgage.get("releaseDate"))
                .addValue("active", mortgage.get("activeOnAssessment")));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise EC payload", e);
        }
    }
}
