package in.gov.slate.surveyor;

import java.time.LocalDate;
import java.util.Map;

import in.gov.slate.survey.SiteVisitSupport;

/** Where a Surveyor's record stands, from booking the survey slot through to VAO verification. */
public enum SurveyorStage {
    AWAITING_PROPOSAL("Book a visit slot", true),
    SLOT_BOOKED("Slot booked", false),
    CHECK_IN_DUE("Check in at site", true),
    SURVEY_DUE("Fill survey form", true),
    CONFLICT_FLAGGED("Area conflict flagged", true),
    SUBMITTED("Submitted to VAO", false),
    OBJECTION_PENDING("Objection raised", false),
    VERIFIED("VAO verified", false);

    private final String label;
    private final boolean actionRequired;

    SurveyorStage(String label, boolean actionRequired) {
        this.label = label;
        this.actionRequired = actionRequired;
    }

    public String label() {
        return label;
    }

    public boolean actionRequired() {
        return actionRequired;
    }

    static SurveyorStage of(Map<String, Object> row, LocalDate today) {
        String txnStatus = (String) row.get("status");
        if ("TAHSILDAR_PENDING".equals(txnStatus) || "REVENUE_APPROVED".equals(txnStatus)) {
            return VERIFIED;
        }
        if ("OBJECTION_PENDING".equals(txnStatus)) {
            return OBJECTION_PENDING;
        }
        if (!"SURVEY_PENDING".equals(txnStatus)) {
            return SUBMITTED;
        }
        if ("SURVEY_CORRECTION_REVIEW".equals(row.get("routed_to"))) {
            return CONFLICT_FLAGGED;
        }
        if (SiteVisitSupport.slotBooked(row)) {
            if (row.get("surveyor_checkin_at") != null) {
                return SURVEY_DUE;
            }
            Object agreed = row.get("agreed_date");
            LocalDate agreedDate = agreed == null ? null : LocalDate.parse(agreed.toString());
            return agreedDate != null && !agreedDate.isAfter(today) ? CHECK_IN_DUE : SLOT_BOOKED;
        }
        return AWAITING_PROPOSAL;
    }
}
