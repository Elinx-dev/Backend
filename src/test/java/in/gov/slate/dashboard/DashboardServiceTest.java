package in.gov.slate.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import in.gov.slate.common.ApiException;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T07:00:00Z"), DashboardFilter.ZONE);

    @Mock
    private DashboardRepository repository;

    @Test
    void assemblesOverviewForSelectedState() {
        when(repository.states()).thenReturn(List.of(Map.of("code", "TN", "name", "Tamil Nadu"),
                Map.of("code", "KA", "name", "Karnataka")));
        when(repository.summary(any())).thenReturn(Map.of("transactionsInitiated", 4L));
        when(repository.failureOverview(any())).thenReturn(Map.of("ruleDiscrepancies", 1L));
        when(repository.vaoActivity(any())).thenReturn(Map.of("objectionsRaised", 2L));

        Map<String, Object> result = new DashboardService(repository, CLOCK).overview("KA", "WEEK", null, null);

        assertThat(result).containsKeys("summary", "transactionsByStatus", "trend", "ruleChecks", "failures",
                "revenue", "byOffice", "byState", "recentIssues", "states");
        assertThat(result.get("filter")).isEqualTo(Map.of("stateCode", "KA", "period", "WEEK",
                "from", "2026-09-26", "to", "2026-10-02", "bucket", "DAY", "timezone", "Asia/Kolkata"));
        @SuppressWarnings("unchecked")
        Map<String, Object> failures = (Map<String, Object>) result.get("failures");
        assertThat(failures).containsEntry("ruleDiscrepancies", 1L).containsKeys("ruleReasons", "failedActionsByType");
        verify(repository).summary(argThat(f -> "KA".equals(f.stateCode())));
    }

    @Test
    void searchesRecordsWithinResolvedFilter() {
        when(repository.states()).thenReturn(List.of(Map.of("code", "TN", "name", "Tamil Nadu")));
        when(repository.records(any(), any())).thenReturn(Map.of("total", 1L, "rows", List.of(Map.of("txnRef", "T-1"))));

        Map<String, Object> result = new DashboardService(repository, CLOCK)
                .records("TN", "TODAY", null, null, "transactions", "T-1", "REGISTERED");

        assertThat(result).containsEntry("dataset", "TRANSACTIONS").containsEntry("total", 1L)
                .containsEntry("limit", DashboardRecordQuery.LIMIT).containsEntry("status", "REGISTERED");
        verify(repository).records(argThat(f -> "TN".equals(f.stateCode())),
                argThat(q -> "T-1".equals(q.search()) && q.dataset() == DashboardRecordQuery.Dataset.TRANSACTIONS));
    }

    @Test
    void rejectsStateThatIsNotConfigured() {
        when(repository.states()).thenReturn(List.of(Map.of("code", "TN", "name", "Tamil Nadu")));

        assertThatThrownBy(() -> new DashboardService(repository, CLOCK).overview("MH", "TODAY", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown state code MH");
        verify(repository, never()).summary(any());
    }
}
