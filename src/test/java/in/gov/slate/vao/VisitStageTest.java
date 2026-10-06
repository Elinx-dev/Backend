package in.gov.slate.vao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class VisitStageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private static Map<String, Object> row(String txnStatus, String visitStatus) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", txnStatus);
        row.put("visit_status", visitStatus);
        return row;
    }

    @Test
    void recordWithoutVisitNeedsABooking() {
        VisitStage stage = VisitStage.of(row("SURVEY_PENDING", null), TODAY);
        assertThat(stage).isEqualTo(VisitStage.AWAITING_PROPOSAL);
        assertThat(stage.actionRequired()).isTrue();
    }

    @Test
    void surveyorProposalIsTheVaosTurn() {
        Map<String, Object> row = row("SURVEY_PENDING", "PROPOSED");
        row.put("proposed_by_role", "SURVEYOR");
        assertThat(VisitStage.of(row, TODAY)).isEqualTo(VisitStage.SURVEYOR_PROPOSED);

        row.put("visit_status", "COUNTER_PROPOSED");
        row.put("counter_by_role", "VAO");
        assertThat(VisitStage.of(row, TODAY)).isEqualTo(VisitStage.VAO_COUNTERED);
        assertThat(VisitStage.of(row, TODAY).actionRequired()).isFalse();
    }

    @Test
    void bookedVisitBecomesCheckInDueOnTheVisitDate() {
        Map<String, Object> row = row("SURVEY_PENDING", "ACCEPTED");
        row.put("agreed_date", "2026-10-10");
        assertThat(VisitStage.of(row, TODAY)).isEqualTo(VisitStage.SLOT_BOOKED);
        row.put("agreed_date", "2026-10-08");
        assertThat(VisitStage.of(row, TODAY)).isEqualTo(VisitStage.CHECK_IN_DUE);
        row.put("vao_checkin_at", "2026-10-08T10:31:00Z");
        assertThat(VisitStage.of(row, TODAY)).isEqualTo(VisitStage.VISIT_DONE);
    }

    @Test
    void vaoCanOnlyVerifyOnceASlotIsBooked() {
        Map<String, Object> unbooked = row("VAO_PENDING", null);
        unbooked.put("mutation_status", "VAO_PENDING");
        Map<String, Object> enriched = VaoService.enrich(unbooked, TODAY);
        assertThat(enriched.get("stage")).isEqualTo("AWAITING_PROPOSAL");
        assertThat(enriched.get("can_verify")).isEqualTo(false);

        Map<String, Object> booked = row("VAO_PENDING", "ACCEPTED");
        booked.put("mutation_status", "VAO_PENDING");
        booked.put("agreed_date", "2026-10-09");
        enriched = VaoService.enrich(booked, TODAY);
        assertThat(enriched.get("stage")).isEqualTo("READY_TO_VERIFY");
        assertThat(enriched.get("can_verify")).isEqualTo(true);
    }

    @Test
    void forwardedRecordsCountAsVerified() {
        assertThat(VisitStage.of(row("TAHSILDAR_PENDING", "ACCEPTED"), TODAY)).isEqualTo(VisitStage.VERIFIED);
    }
}
