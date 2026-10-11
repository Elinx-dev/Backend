package in.gov.slate.vao;

import java.time.LocalDate;
import java.util.Map;

import in.gov.slate.survey.SiteVisitSupport;

/**
 * Where a VAO record stands, derived from its transaction and the VAO's own field-verification
 * visit. The VAO's stage starts once the Surveyor has submitted the survey.
 */
public enum VisitStage {
    WITH_SURVEYOR("Survey in progress", false),
    AWAITING_PROPOSAL("Book a visit slot", true),
    READY_TO_VERIFY("Ready to verify", true),
    OBJECTION_PENDING("Objection pending", true),
    VERIFIED("Verified & forwarded", false);

    private final String label;
    private final boolean actionRequired;

    VisitStage(String label, boolean actionRequired) {
        this.label = label;
        this.actionRequired = actionRequired;
    }

    public String label() {
        return label;
    }

    public boolean actionRequired() {
        return actionRequired;
    }

    static VisitStage of(Map<String, Object> row, LocalDate today) {
        String txnStatus = (String) row.get("status");
        if ("TAHSILDAR_PENDING".equals(txnStatus) || "REVENUE_APPROVED".equals(txnStatus)) {
            return VERIFIED;
        }
        if ("OBJECTION_PENDING".equals(txnStatus)) {
            return OBJECTION_PENDING;
        }
        if ("SURVEY_PENDING".equals(txnStatus)) {
            return WITH_SURVEYOR;
        }
        return SiteVisitSupport.slotBooked(row) ? READY_TO_VERIFY : AWAITING_PROPOSAL;
    }
}
