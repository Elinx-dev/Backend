package in.gov.slate.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import in.gov.slate.common.ApiException;

class ScheduleValueTest {

    @Test
    void surveyValuesAreTotalledIntoTheSchedule() {
        var schedule = new TransactionService.ScheduleInput("Schedule A", new BigDecimal("1"), List.of(
                new TransactionService.ScheduleSurveyInput("100", "1", new BigDecimal("600000")),
                new TransactionService.ScheduleSurveyInput("101", "2", new BigDecimal("400000"))));
        assertThat(TransactionService.scheduleValue(schedule)).isEqualByComparingTo("1000000");
    }

    @Test
    void manualValueIsUsedWithoutSurveyValues() {
        var schedule = new TransactionService.ScheduleInput("Schedule B", new BigDecimal("600000"), List.of(
                new TransactionService.ScheduleSurveyInput("100", "1", null)));
        assertThat(TransactionService.scheduleValue(schedule)).isEqualByComparingTo("600000");
    }

    @Test
    void scheduleNeedsAPositiveValue() {
        var schedule = new TransactionService.ScheduleInput("Schedule C", null, List.of());
        assertThatThrownBy(() -> TransactionService.scheduleValue(schedule))
                .isInstanceOf(ApiException.class).hasMessageContaining("Schedule C");
    }
}
