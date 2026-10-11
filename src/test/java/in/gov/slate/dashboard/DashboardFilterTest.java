package in.gov.slate.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import in.gov.slate.common.ApiException;

class DashboardFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    @Test
    void todayCoversTheLocalDayInHourlyBuckets() {
        DashboardFilter f = DashboardFilter.of("tn", "today", null, null, TODAY);

        assertThat(f.stateCode()).isEqualTo("TN");
        assertThat(f.from()).isEqualTo(OffsetDateTime.of(2026, 10, 2, 0, 0, 0, 0, IST));
        assertThat(f.to()).isEqualTo(OffsetDateTime.of(2026, 10, 3, 0, 0, 0, 0, IST));
        assertThat(f.bucket()).isEqualTo(DashboardFilter.Bucket.HOUR);
    }

    @Test
    void weekAndMonthEndTodayInclusive() {
        DashboardFilter week = DashboardFilter.of("ALL", "WEEK", null, null, TODAY);
        DashboardFilter month = DashboardFilter.of(null, null, null, null, TODAY);

        assertThat(week.allStates()).isTrue();
        assertThat(week.fromDate()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(week.toDate()).isEqualTo(TODAY);
        assertThat(week.bucket()).isEqualTo(DashboardFilter.Bucket.DAY);
        assertThat(month.period()).isEqualTo(DashboardFilter.Period.MONTH);
        assertThat(month.fromDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void customRangeIsInclusiveOfTheEndDate() {
        DashboardFilter f = DashboardFilter.of("KA", "CUSTOM", "2026-01-01", "2026-01-31", TODAY);

        assertThat(f.from()).isEqualTo(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, IST));
        assertThat(f.to()).isEqualTo(OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, IST));
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> DashboardFilter.of("TN'; --", "WEEK", null, null, TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("Unknown state code");
        assertThatThrownBy(() -> DashboardFilter.of("TN", "YEAR", null, null, TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("Period must be one of");
        assertThatThrownBy(() -> DashboardFilter.of("TN", "CUSTOM", "2026-02-01", null, TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("'to' is required");
        assertThatThrownBy(() -> DashboardFilter.of("TN", "CUSTOM", "2026-02-01", "2026-01-01", TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("on or before");
        assertThatThrownBy(() -> DashboardFilter.of("TN", "CUSTOM", "2024-01-01", "2026-01-01", TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot exceed");
        assertThatThrownBy(() -> DashboardFilter.of("TN", "CUSTOM", "01/02/2026", "2026-03-01", TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("YYYY-MM-DD");
    }
}
