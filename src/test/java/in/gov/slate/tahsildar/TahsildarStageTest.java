package in.gov.slate.tahsildar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TahsildarStageTest {

    private static Map<String, Object> ready() {
        Map<String, Object> row = new HashMap<>();
        row.put("status", "TAHSILDAR_PENDING");
        row.put("mutation_status", "TAHSILDAR_PENDING");
        row.put("vao_verified_at", "2026-10-01T10:00:00Z");
        row.put("visit_booked", true);
        row.put("survey_required", true);
        row.put("within_tolerance", true);
        row.put("registered_document_no", "DOC/2026/1");
        row.put("open_objections", 0L);
        return row;
    }

    @Test
    void allChecksPassedIsReadyForApproval() {
        Map<String, Object> row = TahsildarService.enrich(ready());
        assertThat(row.get("stage")).isEqualTo("READY_FOR_APPROVAL");
        assertThat(row.get("can_approve")).isEqualTo(true);
    }

    @Test
    void surveyConflictPutsTheRecordOnHold() {
        Map<String, Object> raw = ready();
        raw.put("within_tolerance", false);
        Map<String, Object> row = TahsildarService.enrich(raw);
        assertThat(row.get("stage")).isEqualTo("ON_HOLD");
        assertThat(row.get("can_approve")).isEqualTo(false);
        assertThat(ApprovalChecklist.failed(ApprovalChecklist.evaluate(raw)))
                .containsExactly("Survey within tolerance");
    }

    @Test
    void surveyIsNotCheckedWhenNotRequired() {
        Map<String, Object> raw = ready();
        raw.put("survey_required", false);
        raw.remove("within_tolerance");
        assertThat(ApprovalChecklist.failed(ApprovalChecklist.evaluate(raw))).isEmpty();
    }

    @Test
    void approvedAndUpstreamStagesAreNotActionable() {
        Map<String, Object> approved = ready();
        approved.put("status", "COMPLETED");
        approved.put("mutation_status", "REVENUE_APPROVED");
        assertThat(TahsildarService.enrich(approved).get("stage")).isEqualTo("APPROVED");

        Map<String, Object> withVao = ready();
        withVao.put("status", "VAO_PENDING");
        withVao.put("mutation_status", "VAO_PENDING");
        Map<String, Object> row = TahsildarService.enrich(withVao);
        assertThat(row.get("stage")).isEqualTo("WITH_VAO");
        assertThat(row.get("action_required")).isEqualTo(false);
    }
}
