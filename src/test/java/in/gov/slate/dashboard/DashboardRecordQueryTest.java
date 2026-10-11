package in.gov.slate.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import in.gov.slate.common.ApiException;
import in.gov.slate.dashboard.DashboardRecordQuery.Dataset;

class DashboardRecordQueryTest {

    @Test
    void defaultsToTransactionsWithoutFilters() {
        DashboardRecordQuery q = DashboardRecordQuery.of(null, "  ", "all");

        assertThat(q.dataset()).isEqualTo(Dataset.TRANSACTIONS);
        assertThat(q.search()).isNull();
        assertThat(q.status()).isNull();
        assertThat(q.likePattern()).isNull();
    }

    @Test
    void normalisesDatasetAndStatus() {
        DashboardRecordQuery q = DashboardRecordQuery.of("rule_checks", " SRO-ADYAR ", "discrepancy_detected");

        assertThat(q.dataset()).isEqualTo(Dataset.RULE_CHECKS);
        assertThat(q.search()).isEqualTo("SRO-ADYAR");
        assertThat(q.status()).isEqualTo("DISCREPANCY_DETECTED");
    }

    @Test
    void escapesLikeWildcards() {
        assertThat(DashboardRecordQuery.of("ISSUES", "50%_off\\", null).likePattern())
                .isEqualTo("%50\\%\\_off\\\\%");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> DashboardRecordQuery.of("USERS", null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("Unknown dataset");
        assertThatThrownBy(() -> DashboardRecordQuery.of(null, "x".repeat(101), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot exceed");
        assertThatThrownBy(() -> DashboardRecordQuery.of(null, null, "REGISTERED' OR 1=1"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Invalid status");
        assertThatThrownBy(() -> DashboardRecordQuery.of("ISSUES", null, "REGISTERED"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Issue type");
    }
}
