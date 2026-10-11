package in.gov.slate.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PartitionParticipantsTest {

    private static Map<String, Object> member(long id, Long parent, String ref, String type, String name,
                                              String relationship, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("parent_member_id", parent);
        m.put("member_ref", ref);
        m.put("member_type", type);
        m.put("name", name);
        m.put("relationship", relationship);
        m.put("living_status", status);
        return m;
    }

    @Test
    void livingOwnersAndLivingHeirsAtAnyDepthParticipateWithTheirLineage() {
        List<Map<String, Object>> members = List.of(
                member(1, null, "O1", "OWNER", "A", null, "LIVING"),
                member(2, null, "O2", "OWNER", "B", null, "DECEASED"),
                member(3, 2L, "O2.1", "HEIR", "B1", "Son", "LIVING"),
                member(4, 2L, "O2.2", "HEIR", "B2", "Daughter", "DECEASED"),
                member(5, 4L, "O2.2.1", "HEIR", "B2.1", "Son", "LIVING"));

        List<Map<String, Object>> participants = PartitionService.participants(members);

        assertThat(participants).extracting(p -> p.get("memberRef")).containsExactly("O1", "O2.1", "O2.2.1");
        assertThat(participants.get(0).get("relationship")).isEqualTo("Current owner");
        assertThat(participants.get(2).get("sourceBranch")).isEqualTo("B");
        assertThat(participants.get(2).get("lineage")).isEqualTo("B (deceased) → B2 (deceased) → B2.1");
    }
}
