package in.gov.slate.tahsildar;

import java.util.List;
import java.util.Map;

/** Where a record in the Tahsildar's taluk stands, from the Tahsildar's point of view. */
public enum TahsildarStage {
    WITH_SURVEYOR("With Surveyor", false),
    WITH_VAO("With VAO", false),
    OBJECTION_PENDING("Objection pending", false),
    READY_FOR_APPROVAL("Ready for approval", true),
    ON_HOLD("Checks pending", true),
    APPROVED("Approved", false);

    private final String label;
    private final boolean actionRequired;

    TahsildarStage(String label, boolean actionRequired) {
        this.label = label;
        this.actionRequired = actionRequired;
    }

    public String label() {
        return label;
    }

    public boolean actionRequired() {
        return actionRequired;
    }

    public static TahsildarStage of(Map<String, Object> row, List<Map<String, Object>> checklist) {
        String status = (String) row.get("status");
        if ("REVENUE_APPROVED".equals(status) || "REVENUE_APPROVED".equals(row.get("mutation_status"))) {
            return APPROVED;
        }
        if ("SURVEY_PENDING".equals(status)) {
            return WITH_SURVEYOR;
        }
        if ("OBJECTION_PENDING".equals(status)) {
            return OBJECTION_PENDING;
        }
        if ("TAHSILDAR_PENDING".equals(status)) {
            return ApprovalChecklist.failed(checklist).isEmpty() ? READY_FOR_APPROVAL : ON_HOLD;
        }
        return WITH_VAO;
    }
}
