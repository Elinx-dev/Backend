package in.gov.slate.vao;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

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

    static final Set<String> BOOKED_VISIT_STATUSES = Set.of("ACCEPTED", "COMPLETED");

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

    /** Whose turn it is on an open proposal: the proposer, or the role that countered it. */
    static String lastMover(Map<String, Object> row) {
        return "COUNTER_PROPOSED".equals(row.get("visit_status"))
                ? (String) row.get("counter_by_role")
                : (String) row.get("proposed_by_role");
    }

    static boolean slotBooked(Map<String, Object> row) {
        Object status = row.get("visit_status");
        return status != null && BOOKED_VISIT_STATUSES.contains(status.toString());
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
        boolean booked = slotBooked(row);
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
        boolean surveyorTurn = "SURVEYOR".equals(lastMover(row));
        if ("COUNTER_PROPOSED".equals(visitStatus)) {
            return surveyorTurn ? SURVEYOR_COUNTERED : VAO_COUNTERED;
        }
        return surveyorTurn ? SURVEYOR_PROPOSED : VAO_PROPOSED;
    }
}
