package in.gov.slate.surveyor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SurveyorStageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private static Map<String, Object> row(String status, String visitStatus) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", status);
        row.put("visit_status", visitStatus);
        return row;
    }

    @Test
    void noVisitMeansTheSurveyorMustBookASlot() {
        SurveyorStage stage = SurveyorStage.of(row("SURVEY_PENDING", null), TODAY);
        assertThat(stage).isEqualTo(SurveyorStage.AWAITING_PROPOSAL);
        assertThat(stage.actionRequired()).isTrue();
    }

    @Test
    void vaoProposalIsTheSurveyorsTurn() {
        Map<String, Object> row = row("SURVEY_PENDING", "PROPOSED");
        row.put("proposed_by_role", "VAO");
        assertThat(SurveyorStage.of(row, TODAY)).isEqualTo(SurveyorStage.VAO_PROPOSED);
    }

    @Test
    void ownCounterProposalWaitsForTheVao() {
        Map<String, Object> row = row("SURVEY_PENDING", "COUNTER_PROPOSED");
        row.put("proposed_by_role", "VAO");
        row.put("counter_by_role", "SURVEYOR");
        SurveyorStage stage = SurveyorStage.of(row, TODAY);
        assertThat(stage).isEqualTo(SurveyorStage.SURVEYOR_COUNTERED);
        assertThat(stage.actionRequired()).isFalse();
    }

    @Test
    void bookedSlotMovesToCheckInOnTheDayAndThenTheSurveyForm() {
        Map<String, Object> row = row("SURVEY_PENDING", "ACCEPTED");
        row.put("agreed_date", "2026-10-09");
        assertThat(SurveyorStage.of(row, TODAY)).isEqualTo(SurveyorStage.SLOT_BOOKED);
        row.put("agreed_date", "2026-10-06");
        assertThat(SurveyorStage.of(row, TODAY)).isEqualTo(SurveyorStage.CHECK_IN_DUE);
        row.put("surveyor_checkin_at", "2026-10-06T09:05:00Z");
        assertThat(SurveyorStage.of(row, TODAY)).isEqualTo(SurveyorStage.SURVEY_DUE);
    }

    @Test
    void outOfToleranceSubmissionIsFlagged() {
        Map<String, Object> row = row("SURVEY_PENDING", "ACCEPTED");
        row.put("routed_to", "SURVEY_CORRECTION_REVIEW");
        assertThat(SurveyorStage.of(row, TODAY)).isEqualTo(SurveyorStage.CONFLICT_FLAGGED);
    }

    @Test
    void laterStatusesFollowTheVao() {
        assertThat(SurveyorStage.of(row("VAO_PENDING", "COMPLETED"), TODAY)).isEqualTo(SurveyorStage.SUBMITTED);
        assertThat(SurveyorStage.of(row("TAHSILDAR_PENDING", "COMPLETED"), TODAY)).isEqualTo(SurveyorStage.VERIFIED);
    }
}
