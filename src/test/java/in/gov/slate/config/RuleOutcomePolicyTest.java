package in.gov.slate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import in.gov.slate.common.ApiException;

class RuleOutcomePolicyTest {

    @Test
    void defaultAllowsOnlyCleanAndNotChecked() {
        RuleOutcomePolicy policy = RuleOutcomePolicy.DEFAULT;
        assertThat(policy.blocks("NO_DISCREPANCY_DETECTED", "NO_ENCUMBRANCE_FOUND_IN_AVAILABLE_PERIOD")).isFalse();
        assertThat(policy.blocks("NOT_CHECKED", "EC_DATA_UNAVAILABLE")).isFalse();
        assertThat(policy.blocks("REVIEW_REQUIRED", "PARTIAL_COVERAGE")).isTrue();
        assertThat(policy.blocks("DISCREPANCY_DETECTED", "OPEN_MORTGAGE")).isTrue();
    }

    @Test
    void blockingReasonStopsEvenWhenOutcomeIsAllowed() {
        RuleOutcomePolicy policy = new RuleOutcomePolicy(
                Set.of("NO_DISCREPANCY_DETECTED", "NOT_CHECKED", "DISCREPANCY_DETECTED"), Set.of("COURT_ATTACHMENT"));
        assertThat(policy.blocks("DISCREPANCY_DETECTED", "OPEN_MORTGAGE")).isFalse();
        assertThat(policy.blocks("DISCREPANCY_DETECTED", "COURT_ATTACHMENT")).isTrue();
    }

    @Test
    void allowedOutcomesKeepCanonicalOrder() {
        RuleOutcomePolicy policy = RuleOutcomePolicy.validated(
                List.of("NOT_CHECKED", "NO_DISCREPANCY_DETECTED"), List.of());
        assertThat(policy.allowedOutcomes()).containsExactly("NO_DISCREPANCY_DETECTED", "NOT_CHECKED");
    }

    @Test
    void rejectsPolicyThatWouldStopCleanResults() {
        assertThatThrownBy(() -> RuleOutcomePolicy.validated(List.of("NOT_CHECKED"), List.of()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RuleOutcomePolicy.validated(List.of("NO_DISCREPANCY_DETECTED", "MAYBE"), List.of()))
                .isInstanceOf(ApiException.class);
    }
}
