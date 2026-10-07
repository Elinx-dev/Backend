package in.gov.slate.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.RevenueLandContext;
import in.gov.slate.config.ConfigService;
import in.gov.slate.connectors.RevenueConnector;
import in.gov.slate.connectors.model.RevenueModels.RevenueLookupRequest;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwner;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwnershipResponse;
import in.gov.slate.connectors.model.RevenueModels.RevenueParcel;
import in.gov.slate.survey.AreaUnits;
import in.gov.slate.transaction.TransactionContext;

/**
 * Revenue / Patta ownership check. Every survey/subdivision row of the property is looked
 * up by district, taluk, revenue village and land type (the patta number is display-only).
 * The Revenue record must describe the same property (identity and the complete unordered
 * survey/subdivision set), its current owners must be exactly the Aadhaar-verified
 * owner-side parties, and each survey row's extent must match after unit conversion.
 * Related survey numbers (100 vs 100A) are never assumed to be the same parcel unless a
 * verified, sourced survey lineage links them.
 */
@Component
public class RevenueOwnershipEngine implements RuleEngine {

    static final String STANDARD_UNIT = "SQ_M";

    private static final Pattern BASE_SURVEY = Pattern.compile("^(\\d+)");
    private static final List<String> DISCREPANCY_PRIORITY = List.of("PROPERTY_IDENTITY_MISMATCH",
            "REVENUE_RECORD_NOT_FOUND", "SURVEY_SET_MISMATCH", "OWNER_MISMATCH", "EXTENT_MISMATCH");
    private static final List<String> REVIEW_PRIORITY = List.of("MULTIPLE_REVENUE_RECORDS", "IDENTITY_UNCLEAR",
            "SURVEY_LINK_UNCONFIRMED", "EXTENT_MISSING", "EXTENT_UNCONVERTIBLE");

    private final RevenueConnector connector;
    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService config;
    private final ObjectMapper mapper;

    public RevenueOwnershipEngine(RevenueConnector connector, NamedParameterJdbcTemplate jdbc, ConfigService config,
                                  ObjectMapper mapper) {
        this.connector = connector;
        this.jdbc = jdbc;
        this.config = config;
        this.mapper = mapper;
    }

    record SlateParcel(String surveyNo, String subdivisionNo, BigDecimal extent, String extentUnit) {
    }

    record Subject(String district, String taluk, String village, String landType, List<SlateParcel> parcels) {
    }

    /** One Revenue lookup for one SLATE survey row; lineageApplied when an authoritative old number was used. */
    record Lookup(SlateParcel slate, RevenueOwnershipResponse response, boolean lineageApplied) {
    }

    /** True only when a verified, sourced survey lineage links the two survey/subdivision numbers. */
    @FunctionalInterface
    interface SurveyLinkage {
        boolean linked(String slateSurvey, String slateSub, String revenueSurvey, String revenueSub);
    }

    record OwnerSide(List<String> names, String problemReason, String problemNote) {
    }

    record Evaluation(String outcome, String reason, String summary, Map<String, Object> details) {
    }

    @Override
    public String engine() {
        return "REVENUE_OWNERSHIP";
    }

    @Override
    public Map<String, Object> requestPayload(TransactionContext ctx, LocalDate assessmentDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engine", engine());
        payload.put("propertyRef", ctx.propertyRef());
        payload.putAll(searchedWith(subject(ctx)));
        return payload;
    }

    @Override
    public Outcome run(TransactionContext ctx, long requestId, LocalDate assessmentDate, String mode) {
        Subject subject = subject(ctx);
        Map<String, Object> searched = searchedWith(subject);
        String stateCode = (String) ctx.transaction().get("state_code");
        Map<String, Object> engineConfig = config.ruleEngineConfig(stateCode, engine());

        String missing = missingPropertyData(subject, textList(engineConfig.get("supported_land_types")));
        if (missing != null) {
            return notChecked("PROPERTY_DATA_MISSING", searched, missing, List.of());
        }
        OwnerSide ownerSide = ownerSide(ctx);
        if (ownerSide.problemReason() != null) {
            return notChecked(ownerSide.problemReason(), searched, ownerSide.problemNote(), List.of());
        }

        boolean applyLineage = !Boolean.FALSE.equals(engineConfig.get("apply_survey_lineage"));
        List<Lookup> lookups = new ArrayList<>();
        for (SlateParcel parcel : subject.parcels()) {
            RevenueOwnershipResponse response = lookup(subject, parcel.surveyNo(), parcel.subdivisionNo());
            boolean lineageApplied = false;
            if (applyLineage && response != null && "NOT_FOUND".equals(response.responseStatus())) {
                String[] target = lineageTarget(ctx, parcel);
                if (target != null) {
                    response = lookup(subject, target[0], target[1]);
                    lineageApplied = true;
                }
            }
            lookups.add(new Lookup(parcel, response, lineageApplied));
        }
        lookups.forEach(lookup -> persistLookup(requestId, ctx, lookup));

        Evaluation evaluation = evaluate(subject, ownerSide.names(), lookups,
                (slateSurvey, slateSub, revSurvey, revSub) ->
                        authoritativeLink(ctx, slateSurvey, slateSub, revSurvey, revSub));
        persistNameMatches(requestId, evaluation);

        boolean blocking = config.blockingRuleReasons(stateCode).getOrDefault(engine(), Set.of())
                .contains(evaluation.reason());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", evaluation.summary());
        payload.put("blocking", blocking);
        payload.put("advisory", !blocking);
        payload.put("searchedWith", searched);
        payload.put("ownerSide", ctx.deedType().get("owner_side"));
        payload.putAll(evaluation.details());
        return new Outcome(evaluation.outcome(), evaluation.reason(), payload);
    }

    private RevenueOwnershipResponse lookup(Subject subject, String surveyNo, String subdivisionNo) {
        try {
            return connector.lookup(new RevenueLookupRequest(subject.district(), subject.taluk(), subject.village(),
                    surveyNo, subdivisionNo, subject.landType()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- evaluation

    static Evaluation evaluate(Subject subject, List<String> slateOwners, List<Lookup> lookups, SurveyLinkage linkage) {
        List<Map<String, Object>> lookupRows = lookups.stream().map(RevenueOwnershipEngine::lookupView).toList();

        for (Lookup lookup : lookups) {
            RevenueOwnershipResponse r = lookup.response();
            if (r == null || r.responseStatus() == null || "UNAVAILABLE".equals(r.responseStatus())) {
                return notCheckedEvaluation("REVENUE_DATA_UNAVAILABLE",
                        "The Revenue / Patta source did not return a usable response for survey "
                                + parcelRef(lookup.slate().surveyNo(), lookup.slate().subdivisionNo())
                                + "; this is not a discrepancy", lookupRows);
            }
        }

        Map<String, RevenueOwnershipResponse> records = new LinkedHashMap<>();
        Set<String> unresolvedSlate = new HashSet<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Lookup lookup : lookups) {
            RevenueOwnershipResponse r = lookup.response();
            String slateRef = parcelRef(lookup.slate().surveyNo(), lookup.slate().subdivisionNo());
            switch (r.responseStatus()) {
                case "FOUND" -> {
                    if (r.allParcels().isEmpty() || r.owners() == null || r.owners().isEmpty()) {
                        return notCheckedEvaluation("REVENUE_RESPONSE_UNUSABLE", "The Revenue record for survey "
                                + slateRef + " has no survey rows or no current owners", lookupRows);
                    }
                    records.putIfAbsent(recordKey(r, records.size()), r);
                }
                case "MULTIPLE" -> {
                    unresolvedSlate.add(parcelKey(lookup.slate().surveyNo(), lookup.slate().subdivisionNo()));
                    findings.add(finding("REVIEW", "MULTIPLE_REVENUE_RECORDS", slateRef,
                            "More than one Revenue record matches survey " + slateRef
                                    + (r.multipleRecordRefs() == null ? "" : " " + r.multipleRecordRefs())
                                    + "; it cannot be resolved automatically"));
                }
                case "NOT_FOUND" -> {
                    // Reported by the survey set comparison below.
                }
                default -> {
                    return notCheckedEvaluation("REVENUE_RESPONSE_UNUSABLE", "The Revenue source returned status "
                            + r.responseStatus() + " for survey " + slateRef, lookupRows);
                }
            }
        }

        List<Map<String, Object>> identity = identityRows(subject, records, findings);
        List<Map<String, Object>> parcels = parcelRows(subject, records, unresolvedSlate, linkage, findings);
        if (records.isEmpty() && unresolvedSlate.isEmpty()) {
            findings.add(0, finding("DISCREPANCY", "REVENUE_RECORD_NOT_FOUND", null,
                    "No Revenue record was found for any survey row of this property"));
        }
        Map<String, Object> ownerResult = ownerRows(slateOwners, records, findings);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("propertyResult", groupResult(findings, Set.of("PROPERTY_IDENTITY_MISMATCH", "IDENTITY_UNCLEAR",
                "REVENUE_RECORD_NOT_FOUND", "SURVEY_SET_MISMATCH", "SURVEY_LINK_UNCONFIRMED",
                "MULTIPLE_REVENUE_RECORDS"), "PROPERTY_MATCH", "PROPERTY_MISMATCH"));
        details.put("ownerResult", ownerResult.get("result"));
        details.put("extentResult", extentResult(parcels, findings));
        details.put("revenueRecords", records.values().stream().map(r -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("recordNumberDisplayOnly", r.recordNumber());
            view.put("sourceReference", r.sourceReference());
            view.put("classification", r.classification());
            return view;
        }).toList());
        details.put("identity", identity);
        details.put("parcels", parcels);
        details.put("slateOwners", slateOwners);
        details.put("owners", ownerResult.get("rows"));
        details.put("lookups", lookupRows);
        details.put("findings", findings);

        String discrepancy = firstByPriority(findings, "DISCREPANCY", DISCREPANCY_PRIORITY);
        if (discrepancy != null) {
            return new Evaluation("DISCREPANCY_DETECTED", discrepancy, summaryOf(findings, "DISCREPANCY"), details);
        }
        String review = firstByPriority(findings, "REVIEW", REVIEW_PRIORITY);
        if (review != null) {
            return new Evaluation("REVIEW_REQUIRED", review, summaryOf(findings, "REVIEW"), details);
        }
        return new Evaluation("NO_DISCREPANCY_DETECTED", "OWNERSHIP_CONSISTENT",
                "District, taluk, village, land type, all " + parcels.size()
                        + " survey row(s), the owner set and every survey-wise extent match the Revenue record",
                details);
    }

    private static List<Map<String, Object>> identityRows(Subject subject, Map<String, RevenueOwnershipResponse> records,
                                                          List<Map<String, Object>> findings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RevenueOwnershipResponse r : records.values()) {
            String[][] fields = {
                    {"District", subject.district(), r.district()},
                    {"Taluk", subject.taluk(), r.taluk()},
                    {"Revenue village", subject.village(), r.village()},
                    {"Land type", subject.landType(), r.landType()}};
            for (String[] field : fields) {
                String status = isBlank(field[2]) ? "MISSING" : sameText(field[1], field[2]) ? "MATCH" : "MISMATCH";
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("recordNumber", r.recordNumber());
                row.put("field", field[0]);
                row.put("slate", field[1]);
                row.put("revenue", field[2]);
                row.put("status", status);
                rows.add(row);
                if ("MISMATCH".equals(status)) {
                    findings.add(finding("DISCREPANCY", "PROPERTY_IDENTITY_MISMATCH", r.recordNumber(),
                            field[0] + " on the Revenue record (" + field[2] + ") differs from SLATE (" + field[1] + ")"));
                } else if ("MISSING".equals(status)) {
                    findings.add(finding("REVIEW", "IDENTITY_UNCLEAR", r.recordNumber(),
                            "The Revenue record does not state the " + field[0].toLowerCase(Locale.ROOT)));
                }
            }
        }
        return rows;
    }

    private record RevenueRow(RevenueParcel parcel, String recordNumber) {
    }

    private static List<Map<String, Object>> parcelRows(Subject subject, Map<String, RevenueOwnershipResponse> records,
                                                        Set<String> unresolvedSlate, SurveyLinkage linkage,
                                                        List<Map<String, Object>> findings) {
        Map<String, RevenueRow> revenue = new LinkedHashMap<>();
        for (RevenueOwnershipResponse r : records.values()) {
            for (RevenueParcel p : r.allParcels()) {
                revenue.putIfAbsent(parcelKey(p.surveyNo(), p.subdivisionNo()), new RevenueRow(p, r.recordNumber()));
            }
        }
        Set<String> slateKeys = new LinkedHashSet<>();
        subject.parcels().forEach(p -> slateKeys.add(parcelKey(p.surveyNo(), p.subdivisionNo())));

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<SlateParcel> missing = new ArrayList<>();
        Map<SlateParcel, Map<String, Object>> rowFor = new HashMap<>();
        for (SlateParcel slate : subject.parcels()) {
            String key = parcelKey(slate.surveyNo(), slate.subdivisionNo());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slateParcel", parcelRef(slate.surveyNo(), slate.subdivisionNo()));
            row.put("slateExtent", slate.extent());
            row.put("slateUnit", slate.extentUnit());
            rows.add(row);
            rowFor.put(slate, row);
            if (unresolvedSlate.contains(key)) {
                row.put("matchStatus", "UNRESOLVED");
                continue;
            }
            RevenueRow match = revenue.get(key);
            String status = "MATCH";
            if (match == null) {
                for (Map.Entry<String, RevenueRow> candidate : revenue.entrySet()) {
                    RevenueParcel p = candidate.getValue().parcel();
                    if (!slateKeys.contains(candidate.getKey()) && !used.contains(candidate.getKey())
                            && linkage.linked(slate.surveyNo(), slate.subdivisionNo(), p.surveyNo(), p.subdivisionNo())) {
                        match = candidate.getValue();
                        status = "HISTORICAL_MATCH";
                        break;
                    }
                }
            }
            if (match == null) {
                missing.add(slate);
                continue;
            }
            used.add(parcelKey(match.parcel().surveyNo(), match.parcel().subdivisionNo()));
            row.put("matchStatus", status);
            fillRevenue(row, match);
            compareExtent(row, slate, match.parcel(), findings);
        }

        List<RevenueRow> extras = revenue.entrySet().stream()
                .filter(e -> !used.contains(e.getKey()) && !slateKeys.contains(e.getKey()))
                .map(Map.Entry::getValue).toList();
        for (SlateParcel slate : missing) {
            String ref = parcelRef(slate.surveyNo(), slate.subdivisionNo());
            boolean related = extras.stream().anyMatch(e -> related(slate.surveyNo(), e.parcel().surveyNo()));
            Map<String, Object> row = rowFor.get(slate);
            row.put("matchStatus", related ? "LINK_UNCONFIRMED" : "NOT_IN_REVENUE");
            findings.add(related
                    ? finding("REVIEW", "SURVEY_LINK_UNCONFIRMED", ref, "SLATE survey " + ref
                            + " is not on the Revenue record; a related survey number is, but no authoritative "
                            + "linkage confirms they are the same parcel")
                    : finding("DISCREPANCY", "SURVEY_SET_MISMATCH", ref,
                            "SLATE survey " + ref + " is not on the Revenue record"));
        }
        for (RevenueRow extra : extras) {
            String ref = parcelRef(extra.parcel().surveyNo(), extra.parcel().subdivisionNo());
            boolean related = missing.stream().anyMatch(m -> related(m.surveyNo(), extra.parcel().surveyNo()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slateParcel", null);
            row.put("matchStatus", related ? "LINK_UNCONFIRMED" : "NOT_IN_SLATE");
            fillRevenue(row, extra);
            rows.add(row);
            findings.add(related
                    ? finding("REVIEW", "SURVEY_LINK_UNCONFIRMED", ref, "Revenue survey " + ref
                            + " is not in SLATE; it may be related to a SLATE survey but no authoritative linkage exists")
                    : finding("DISCREPANCY", "SURVEY_SET_MISMATCH", ref,
                            "The Revenue record also covers survey " + ref + ", which is not part of this property"));
        }
        return rows;
    }

    private static void fillRevenue(Map<String, Object> row, RevenueRow match) {
        row.put("revenueParcel", parcelRef(match.parcel().surveyNo(), match.parcel().subdivisionNo()));
        row.put("revenueExtent", match.parcel().extent());
        row.put("revenueUnit", match.parcel().extentUnit());
        row.put("recordNumber", match.recordNumber());
    }

    private static void compareExtent(Map<String, Object> row, SlateParcel slate, RevenueParcel revenue,
                                      List<Map<String, Object>> findings) {
        String ref = parcelRef(slate.surveyNo(), slate.subdivisionNo());
        row.put("standardUnit", STANDARD_UNIT);
        if (slate.extent() == null || isBlank(slate.extentUnit())
                || revenue.extent() == null || isBlank(revenue.extentUnit())) {
            row.put("extentStatus", "MISSING");
            findings.add(finding("REVIEW", "EXTENT_MISSING", ref, "Extent value or unit is missing for survey " + ref
                    + (slate.extent() == null || isBlank(slate.extentUnit()) ? " in SLATE" : " on the Revenue record")));
            return;
        }
        BigDecimal slateStd;
        BigDecimal revenueStd;
        try {
            slateStd = standard(slate.extent(), slate.extentUnit());
            revenueStd = standard(revenue.extent(), revenue.extentUnit());
        } catch (RuntimeException e) {
            row.put("extentStatus", "UNCONVERTIBLE");
            findings.add(finding("REVIEW", "EXTENT_UNCONVERTIBLE", ref, "Extent of survey " + ref
                    + " cannot be converted (" + slate.extentUnit() + " / " + revenue.extentUnit() + ")"));
            return;
        }
        row.put("slateStandard", slateStd);
        row.put("revenueStandard", revenueStd);
        boolean same = slateStd.compareTo(revenueStd) == 0;
        row.put("extentStatus", same ? "EXTENT_MATCH" : "EXTENT_MISMATCH");
        if (!same) {
            findings.add(finding("DISCREPANCY", "EXTENT_MISMATCH", ref, "Survey " + ref + ": SLATE "
                    + plain(slate.extent()) + " " + slate.extentUnit() + " (" + plain(slateStd) + " " + STANDARD_UNIT
                    + ") vs Revenue " + plain(revenue.extent()) + " " + revenue.extentUnit() + " ("
                    + plain(revenueStd) + " " + STANDARD_UNIT + ")"));
        }
    }

    static BigDecimal standard(BigDecimal value, String unit) {
        String code = AreaUnits.code(unit);
        BigDecimal converted = AreaUnits.convert(value, code, STANDARD_UNIT);
        if (!STANDARD_UNIT.equals(code) && converted == value) {
            throw new IllegalArgumentException("Unknown unit " + unit);
        }
        return converted.setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> ownerRows(List<String> slateOwners, Map<String, RevenueOwnershipResponse> records,
                                                 List<Map<String, Object>> findings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (records.isEmpty()) {
            return Map.of("result", "NOT_COMPARED", "rows", rows);
        }
        boolean allMatch = true;
        for (RevenueOwnershipResponse r : records.values()) {
            List<String> revenueNames = r.owners().stream().map(RevenueOwner::name).toList();
            List<String> remaining = new ArrayList<>(revenueNames.stream().map(RevenueOwnershipEngine::exactName).toList());
            List<String> unmatchedSlate = new ArrayList<>();
            for (String slate : slateOwners) {
                boolean found = remaining.remove(exactName(slate));
                rows.add(ownerRow(r.recordNumber(), slate, found ? slate : null, found ? "MATCH" : "NOT_IN_REVENUE"));
                if (!found) {
                    unmatchedSlate.add(slate);
                }
            }
            List<String> unmatchedRevenue = new ArrayList<>();
            for (String name : revenueNames) {
                if (remaining.remove(exactName(name))) {
                    unmatchedRevenue.add(name);
                    rows.add(ownerRow(r.recordNumber(), null, name, "NOT_IN_SLATE"));
                }
            }
            if (!unmatchedSlate.isEmpty() || !unmatchedRevenue.isEmpty()) {
                allMatch = false;
                findings.add(finding("DISCREPANCY", "OWNER_MISMATCH", r.recordNumber(),
                        "Revenue owners " + revenueNames + " do not exactly match the Aadhaar-verified owner side "
                                + slateOwners + "; the Revenue / Patta record must be updated"));
            }
        }
        return Map.of("result", allMatch ? "OWNER_SET_MATCH" : "OWNER_MISMATCH", "rows", rows);
    }

    private static Map<String, Object> ownerRow(String record, String slate, String revenue, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recordNumber", record);
        row.put("slateOwner", slate);
        row.put("revenueOwner", revenue);
        row.put("status", status);
        return row;
    }

    /** Exact comparison: only letter case and surrounding/repeated spaces are ignored. */
    static String exactName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String extentResult(List<Map<String, Object>> parcels, List<Map<String, Object>> findings) {
        if (parcels.stream().noneMatch(p -> p.containsKey("extentStatus"))) {
            return "NOT_COMPARED";
        }
        if (hasCode(findings, Set.of("EXTENT_MISMATCH"))) {
            return "EXTENT_MISMATCH";
        }
        if (hasCode(findings, Set.of("EXTENT_MISSING", "EXTENT_UNCONVERTIBLE"))) {
            return "REVIEW_REQUIRED";
        }
        return "EXTENT_SET_MATCH";
    }

    private static String groupResult(List<Map<String, Object>> findings, Set<String> codes, String ok, String bad) {
        if (findings.stream().anyMatch(f -> codes.contains(f.get("code")) && "DISCREPANCY".equals(f.get("severity")))) {
            return bad;
        }
        return hasCode(findings, codes) ? "REVIEW_REQUIRED" : ok;
    }

    private static boolean hasCode(List<Map<String, Object>> findings, Set<String> codes) {
        return findings.stream().anyMatch(f -> codes.contains(f.get("code")));
    }

    private static String firstByPriority(List<Map<String, Object>> findings, String severity, List<String> priority) {
        return priority.stream()
                .filter(code -> findings.stream().anyMatch(f -> code.equals(f.get("code"))
                        && severity.equals(f.get("severity"))))
                .findFirst().orElse(null);
    }

    private static String summaryOf(List<Map<String, Object>> findings, String severity) {
        return String.join("; ", findings.stream().filter(f -> severity.equals(f.get("severity")))
                .map(f -> (String) f.get("message")).toList());
    }

    private static Map<String, Object> finding(String severity, String code, String reference, String message) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("severity", severity);
        f.put("code", code);
        f.put("reference", reference);
        f.put("message", message);
        return f;
    }

    private static Evaluation notCheckedEvaluation(String reason, String note, List<Map<String, Object>> lookups) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lookups", lookups);
        details.put("findings", List.of(finding("NOT_CHECKED", reason, null, note)));
        return new Evaluation("NOT_CHECKED", reason, note, details);
    }

    private Outcome notChecked(String reason, Map<String, Object> searched, String note,
                               List<Map<String, Object>> lookups) {
        Evaluation evaluation = notCheckedEvaluation(reason, note, lookups);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", note);
        payload.put("blocking", false);
        payload.put("advisory", true);
        payload.put("searchedWith", searched);
        payload.putAll(evaluation.details());
        return new Outcome("NOT_CHECKED", reason, payload);
    }

    private static Map<String, Object> lookupView(Lookup lookup) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("surveyNo", lookup.slate().surveyNo());
        view.put("subdivisionNo", lookup.slate().subdivisionNo());
        view.put("responseStatus", lookup.response() == null ? "UNAVAILABLE" : lookup.response().responseStatus());
        view.put("recordNumber", lookup.response() == null ? null : lookup.response().recordNumber());
        view.put("lineageApplied", lookup.lineageApplied());
        return view;
    }

    private static String recordKey(RevenueOwnershipResponse r, int index) {
        if (!isBlank(r.recordNumber())) {
            return "NO:" + r.recordNumber();
        }
        return isBlank(r.sourceReference()) ? "IDX:" + index : "REF:" + r.sourceReference();
    }

    static String parcelKey(String surveyNo, String subdivisionNo) {
        return clean(surveyNo) + "/" + clean(subdivisionNo);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static String parcelRef(String surveyNo, String subdivisionNo) {
        return isBlank(subdivisionNo) ? String.valueOf(surveyNo) : surveyNo + "/" + subdivisionNo;
    }

    /** Same base survey number (100, 100A, 100/1B): possibly linked, never assumed equal. */
    private static boolean related(String a, String b) {
        String baseA = baseSurvey(a);
        return baseA != null && baseA.equals(baseSurvey(b));
    }

    private static String baseSurvey(String survey) {
        Matcher m = BASE_SURVEY.matcher(clean(survey));
        return m.find() ? m.group(1) : null;
    }

    private static boolean sameText(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------- SLATE inputs

    private Subject subject(TransactionContext ctx) {
        Map<String, Object> property = ctx.property();
        List<SlateParcel> parcels = jdbc.queryForList("""
                SELECT survey_no, subdivision_no, extent_value, extent_unit
                  FROM core.property_survey WHERE property_id = :propertyId ORDER BY seq
                """, new MapSqlParameterSource("propertyId", property.get("id"))).stream()
                .map(row -> new SlateParcel((String) row.get("survey_no"), (String) row.get("subdivision_no"),
                        (BigDecimal) row.get("extent_value"), (String) row.get("extent_unit")))
                .toList();
        if (parcels.isEmpty() && !isBlank((String) property.get("survey_no"))) {
            parcels = List.of(new SlateParcel((String) property.get("survey_no"), (String) property.get("subdivision_no"),
                    (BigDecimal) property.get("extent_value"), (String) property.get("extent_unit")));
        }
        return new Subject((String) property.get("district_code"), (String) property.get("taluk_code"),
                (String) property.get("village_code"), (String) property.get("land_type_code"), parcels);
    }

    private static Map<String, Object> searchedWith(Subject subject) {
        Map<String, Object> searched = new LinkedHashMap<>();
        searched.put("district", subject.district());
        searched.put("taluk", subject.taluk());
        searched.put("village", subject.village());
        searched.put("landType", subject.landType());
        searched.put("surveys", subject.parcels().stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("surveyNo", p.surveyNo());
            row.put("subdivisionNo", p.subdivisionNo());
            return row;
        }).toList());
        return searched;
    }

    static String missingPropertyData(Subject subject, List<String> supportedLandTypes) {
        if (isBlank(subject.district()) || isBlank(subject.taluk()) || isBlank(subject.village())) {
            return "The property has no district, taluk or revenue village to search Revenue with";
        }
        if (subject.parcels().isEmpty() || subject.parcels().stream().anyMatch(p -> isBlank(p.surveyNo()))) {
            return "The property has no survey number to search Revenue with";
        }
        if (isBlank(subject.landType())) {
            return "The property has no land type; Revenue is searched by land type";
        }
        if (supportedLandTypes.isEmpty()) {
            return "supported_land_types is not configured in cfg.rule_engine_config for REVENUE_OWNERSHIP";
        }
        if (!supportedLandTypes.contains(subject.landType())) {
            return "Land type " + subject.landType() + " is not covered by the Revenue / Patta check (configured: "
                    + String.join(", ", supportedLandTypes) + ")";
        }
        return null;
    }

    /**
     * The owner side comes from the transaction type master; each party on it must have a
     * verified Aadhaar consent, and that party's name is the Aadhaar-verified name.
     */
    static OwnerSide ownerSide(TransactionContext ctx) {
        Object side = ctx.deedType() == null ? null : ctx.deedType().get("owner_side");
        if (side == null) {
            return new OwnerSide(List.of(), "OWNER_SIDE_NOT_CONFIGURED",
                    "master.transaction_type has no owner side for " + ctx.deedTypeCode());
        }
        List<Map<String, Object>> parties = ctx.side(side.toString());
        if (parties.isEmpty()) {
            return new OwnerSide(List.of(), "OWNER_SIDE_PARTIES_MISSING", "The transaction has no owner-side party");
        }
        Set<Long> verified = new HashSet<>();
        ctx.consents().stream().filter(c -> "VERIFIED".equals(c.get("status")))
                .forEach(c -> verified.add(((Number) c.get("party_id")).longValue()));
        List<String> pending = parties.stream()
                .filter(p -> !verified.contains(((Number) p.get("id")).longValue())
                        || Boolean.FALSE.equals(p.get("aadhaar_captured")))
                .map(p -> (String) p.get("name")).toList();
        if (!pending.isEmpty()) {
            return new OwnerSide(List.of(), "AADHAAR_VERIFICATION_PENDING",
                    "Aadhaar consent is not verified for owner-side party " + String.join(", ", pending));
        }
        return new OwnerSide(parties.stream().map(p -> (String) p.get("name")).filter(Objects::nonNull).toList(),
                null, null);
    }

    private static List<String> textList(Object value) {
        try {
            if (value instanceof java.sql.Array array) {
                value = array.getArray();
            }
        } catch (java.sql.SQLException e) {
            return List.of();
        }
        if (value instanceof Object[] items) {
            return java.util.Arrays.stream(items).map(String::valueOf).toList();
        }
        if (value instanceof Collection<?> items) {
            return items.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String[] lineageTarget(TransactionContext ctx, SlateParcel parcel) {
        var rows = jdbc.queryForList("""
                SELECT to_survey_no, to_subdivision FROM rules.survey_lineage
                 WHERE state_code = :stateCode AND revenue_village = :village
                   AND from_survey_no = :surveyNo
                   AND (from_subdivision IS NOT DISTINCT FROM :subdivisionNo)
                   AND verified_at IS NOT NULL AND source_reference IS NOT NULL
                 ORDER BY effective_date DESC LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.property().get("state_code"))
                .addValue("village", ctx.property().get("village_code"))
                .addValue("surveyNo", parcel.surveyNo())
                .addValue("subdivisionNo", parcel.subdivisionNo()));
        return rows.isEmpty() ? null
                : new String[]{(String) rows.get(0).get("to_survey_no"), (String) rows.get(0).get("to_subdivision")};
    }

    private boolean authoritativeLink(TransactionContext ctx, String slateSurvey, String slateSub,
                                      String revSurvey, String revSub) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM rules.survey_lineage
                 WHERE state_code = :stateCode AND revenue_village = :village
                   AND verified_at IS NOT NULL AND source_reference IS NOT NULL
                   AND ((from_survey_no = :a AND from_subdivision IS NOT DISTINCT FROM :aSub
                         AND to_survey_no = :b AND to_subdivision IS NOT DISTINCT FROM :bSub)
                     OR (from_survey_no = :b AND from_subdivision IS NOT DISTINCT FROM :bSub
                         AND to_survey_no = :a AND to_subdivision IS NOT DISTINCT FROM :aSub))
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.property().get("state_code"))
                .addValue("village", ctx.property().get("village_code"))
                .addValue("a", slateSurvey).addValue("aSub", isBlank(slateSub) ? null : slateSub)
                .addValue("b", revSurvey).addValue("bSub", isBlank(revSub) ? null : revSub), Integer.class);
        return count != null && count > 0;
    }

    // ---------------------------------------------------------------- persistence

    private void persistLookup(long requestId, TransactionContext ctx, Lookup lookup) {
        RevenueOwnershipResponse response = lookup.response();
        String status = response == null ? "UNAVAILABLE" : response.responseStatus();
        String parcelMatch = switch (status == null ? "" : status) {
            case "FOUND" -> "PARCEL_MATCH";
            case "MULTIPLE" -> "MULTIPLE_REVENUE_RECORDS";
            case "NOT_FOUND" -> "PARCEL_NOT_FOUND";
            default -> "SURVEY_IDENTITY_UNRESOLVED";
        };
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO rules.revenue_ownership_snapshot (request_id, property_id, record_no, district_code,
                    taluk_code, revenue_village, survey_no, subdivision_no, land_type, classification,
                    extent_value, extent_unit, record_status, portal_reference, parcel_match_status,
                    lineage_applied, raw_payload)
                VALUES (:requestId, :propertyId, :recordNo, :district, :taluk, :village, :surveyNo, :subdivisionNo,
                    :landType, :classification, :extent, :extentUnit, :recordStatus, :portalRef, :parcelMatch,
                    :lineageApplied, cast(:raw AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("propertyId", ctx.property().get("id"))
                .addValue("recordNo", response == null ? null : response.recordNumber())
                .addValue("district", ctx.property().get("district_code"))
                .addValue("taluk", ctx.property().get("taluk_code"))
                .addValue("village", ctx.property().get("village_code"))
                .addValue("surveyNo", lookup.slate().surveyNo())
                .addValue("subdivisionNo", lookup.slate().subdivisionNo())
                .addValue("landType", snapshotLandType(ctx.property().get("land_type_code")))
                .addValue("classification", response == null ? null : response.classification())
                .addValue("extent", lookup.slate().extent())
                .addValue("extentUnit", lookup.slate().extentUnit())
                .addValue("recordStatus", status)
                .addValue("portalRef", response == null ? null : response.sourceReference())
                .addValue("parcelMatch", parcelMatch)
                .addValue("lineageApplied", lookup.lineageApplied())
                .addValue("raw", json(response)), keyHolder, new String[]{"id"});
        long snapshotId = keyHolder.getKey().longValue();
        if (response == null) {
            return;
        }
        int seq = 0;
        for (RevenueOwner owner : response.owners() == null ? List.<RevenueOwner>of() : response.owners()) {
            jdbc.update("""
                    INSERT INTO rules.revenue_owner (snapshot_id, seq, name, relation_type, related_person_name, share_pct)
                    VALUES (:snapshotId, :seq, :name, :relationType, :relatedName, :sharePct)
                    """, new MapSqlParameterSource()
                    .addValue("snapshotId", snapshotId)
                    .addValue("seq", ++seq)
                    .addValue("name", owner.name())
                    .addValue("relationType", owner.relationType())
                    .addValue("relatedName", owner.relatedPersonName())
                    .addValue("sharePct", owner.sharePct()));
        }
        seq = 0;
        for (RevenueParcel parcel : response.allParcels()) {
            jdbc.update("""
                    INSERT INTO rules.revenue_parcel (snapshot_id, seq, survey_no, subdivision_no, extent_value, extent_unit)
                    VALUES (:snapshotId, :seq, :surveyNo, :subdivisionNo, :extent, :unit)
                    """, new MapSqlParameterSource()
                    .addValue("snapshotId", snapshotId)
                    .addValue("seq", ++seq)
                    .addValue("surveyNo", parcel.surveyNo())
                    .addValue("subdivisionNo", parcel.subdivisionNo())
                    .addValue("extent", parcel.extent())
                    .addValue("unit", parcel.extentUnit()));
        }
    }

    @SuppressWarnings("unchecked")
    private void persistNameMatches(long requestId, Evaluation evaluation) {
        Object rows = evaluation.details().get("owners");
        if (!(rows instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            Map<String, Object> row = (Map<String, Object>) item;
            String slate = (String) row.get("slateOwner");
            if (slate == null) {
                continue;
            }
            boolean match = "MATCH".equals(row.get("status"));
            String revenue = match ? (String) row.get("revenueOwner") : "(no identical name)";
            jdbc.update("""
                    INSERT INTO rules.name_match_audit (request_id, left_name, left_source, right_name, right_source,
                        normalized_left, normalized_right, decision, rationale)
                    VALUES (:requestId, :left, 'AADHAAR_VERIFIED_PARTY', :right, 'REVENUE', :nl, :nr, :decision, :rationale)
                    """, new MapSqlParameterSource()
                    .addValue("requestId", requestId)
                    .addValue("left", slate)
                    .addValue("right", revenue)
                    .addValue("nl", exactName(slate))
                    .addValue("nr", match ? exactName(revenue) : "")
                    .addValue("decision", match ? "MATCH" : "NO_MATCH")
                    .addValue("rationale", match ? "Identical name (case and spacing ignored)"
                            : "No identical name on Revenue record " + row.get("recordNumber")));
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise Revenue payload", e);
        }
    }

    static String snapshotLandType(Object landType) {
        return RevenueLandContext.forLegacySnapshot(landType);
    }
}
