package in.gov.slate.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RevenueOwnershipEngineTest {

    @Test
    void omitsUnsupportedLandTypeFromLegacySnapshotColumn() {
        assertThat(RevenueOwnershipEngine.snapshotLandType("URBAN")).isNull();
        assertThat(RevenueOwnershipEngine.snapshotLandType("RURAL")).isEqualTo("RURAL");
        assertThat(RevenueOwnershipEngine.snapshotLandType("NATHAM")).isEqualTo("NATHAM");
    }
}
