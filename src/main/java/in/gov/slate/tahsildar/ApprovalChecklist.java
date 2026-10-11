package in.gov.slate.tahsildar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conditions the Tahsildar's approval depends on. The same list drives the portal's
 * checklist and the server-side gate in {@code RevenueService.approve}.
 */
public final class ApprovalChecklist {

    /** Readiness columns; expects aliases {@code t} (core.transaction) and {@code m} (revenue.proposed_mutation). */
    public static final String READINESS_COLUMNS = """
            m.id AS mutation_id, m.status AS mutation_status, m.vao_verified_at, t.survey_required,
            rr.registered_document_no, sub.within_tolerance, sub.variance_pct,
            EXISTS (SELECT 1 FROM survey.site_visit v
                     WHERE v.transaction_id = t.id AND v.visit_purpose = 'FIELD_VERIFICATION'
                       AND v.status IN ('ACCEPTED','COMPLETED')) AS visit_booked,
            (SELECT count(*) FROM revenue.objection o
              WHERE o.mutation_id = m.id AND o.disposal_decision IS NULL) AS open_objections
            """;

    public static final String READINESS_JOINS = """
            LEFT JOIN core.registration_result rr ON rr.transaction_id = t.id
            LEFT JOIN LATERAL (SELECT s.id, s.within_tolerance, s.variance_pct, s.submitted_at
                                 FROM survey.submission s WHERE s.transaction_id = t.id
                                ORDER BY s.submitted_at DESC LIMIT 1) sub ON TRUE
            """;

    private ApprovalChecklist() {
    }

    /**
     * Expects the columns mutation_status, vao_verified_at, visit_booked, survey_required,
     * within_tolerance, open_objections and registered_document_no.
     */
    public static List<Map<String, Object>> evaluate(Map<String, Object> row) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("REGISTERED", "Document registered", row.get("registered_document_no") != null,
                row.get("registered_document_no")));
        boolean surveyRequired = Boolean.TRUE.equals(row.get("survey_required"));
        items.add(item("SURVEY_WITHIN_TOLERANCE", "Survey within tolerance",
                !surveyRequired || Boolean.TRUE.equals(row.get("within_tolerance")),
                surveyRequired ? row.get("variance_pct") : "Survey not required"));
        items.add(item("SITE_VISIT_BOOKED", "VAO site visit booked", Boolean.TRUE.equals(row.get("visit_booked")),
                null));
        items.add(item("VAO_VERIFIED", "VAO verified & forwarded", row.get("vao_verified_at") != null,
                row.get("vao_verified_at")));
        long openObjections = row.get("open_objections") instanceof Number n ? n.longValue() : 0;
        items.add(item("NO_OPEN_OBJECTIONS", "No open objections", openObjections == 0,
                openObjections == 0 ? null : openObjections + " open"));
        return items;
    }

    public static List<String> failed(List<Map<String, Object>> checklist) {
        return checklist.stream().filter(i -> !Boolean.TRUE.equals(i.get("passed")))
                .map(i -> (String) i.get("label")).toList();
    }

    private static Map<String, Object> item(String code, String label, boolean passed, Object detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("label", label);
        out.put("passed", passed);
        if (detail != null) {
            out.put("detail", String.valueOf(detail));
        }
        return out;
    }
}
