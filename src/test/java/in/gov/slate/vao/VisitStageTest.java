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
    void surveyPendingRecordsWaitForTheSurveyor() {
        VisitStage stage = VisitStage.of(row("SURVEY_PENDING", "ACCEPTED"), TODAY);
        assertThat(stage).isEqualTo(VisitStage.WITH_SURVEYOR);
        assertThat(stage.actionRequired()).isFalse();
    }

    @Test
    void recordWithTheVaoNeedsTheVaosOwnBooking() {
        VisitStage stage = VisitStage.of(row("VAO_PENDING", null), TODAY);
        assertThat(stage).isEqualTo(VisitStage.AWAITING_PROPOSAL);
        assertThat(stage.actionRequired()).isTrue();
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
