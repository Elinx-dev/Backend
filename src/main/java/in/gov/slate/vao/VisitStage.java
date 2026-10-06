package in.gov.slate.vao;

import java.time.LocalDate;
import java.util.Map;

import in.gov.slate.survey.SiteVisitSupport;

/** Where a VAO record stands in the site-visit lifecycle, derived from its transaction and latest visit. */
public enum VisitStage {
    AWAITING_PROPOSAL("Book a visit slot", true),
    SURVEYOR_PROPOSED("Surveyor proposed a date", true),
    SURVEYOR_COUNTERED("Surveyor counter-proposed", true),
    VAO_PROPOSED("Awaiting Surveyor", false),
    VAO_COUNTERED("Awaiting Surveyor", false),
    SLOT_BOOKED("Slot booked", false),
    CHECK_IN_DUE("Check in at site", true),
    VISIT_DONE("Visit done", false),
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
        String visitStatus = (String) row.get("visit_status");
        boolean booked = SiteVisitSupport.slotBooked(row);
        if ("VAO_PENDING".equals(txnStatus) && booked) {
            return READY_TO_VERIFY;
        }
        if (visitStatus == null) {
            return AWAITING_PROPOSAL;
        }
        if (booked) {
            if (row.get("vao_checkin_at") != null) {
                return VISIT_DONE;
            }
            Object agreed = row.get("agreed_date");
            LocalDate agreedDate = agreed == null ? null : LocalDate.parse(agreed.toString());
            return agreedDate != null && !agreedDate.isAfter(today) ? CHECK_IN_DUE : SLOT_BOOKED;
        }
        boolean surveyorTurn = "SURVEYOR".equals(SiteVisitSupport.lastMover(row));
        if ("COUNTER_PROPOSED".equals(visitStatus)) {
            return surveyorTurn ? SURVEYOR_COUNTERED : VAO_COUNTERED;
        }
        return surveyorTurn ? SURVEYOR_PROPOSED : VAO_PROPOSED;
    }
}
