package in.gov.slate.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.ApiException;

class FeeCalculatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void percentageOnlyAppliesTheRate() {
        assertThat(FeeCalculator.component("stamp duty", new BigDecimal("102"), "PERCENTAGE",
                new BigDecimal("0.07"), null, null, null)).isEqualByComparingTo("7.14");
    }

    @Test
    void percentageWithCapStopsAtTheCap() {
        BigDecimal value = new BigDecimal("6000000");
        assertThat(FeeCalculator.component("stamp duty", value, "PERCENTAGE_WITH_CAP",
                new BigDecimal("0.01"), null, null, new BigDecimal("40000"))).isEqualByComparingTo("40000");
        assertThat(FeeCalculator.component("registration fee", new BigDecimal("500000"), "PERCENTAGE_WITH_CAP",
                new BigDecimal("0.01"), null, null, new BigDecimal("10000"))).isEqualByComparingTo("5000");
    }

    @Test
    void fixedAmountIgnoresTheValue() {
        assertThat(FeeCalculator.component("stamp duty", new BigDecimal("9999999"), "FIXED",
                null, BigDecimal.ZERO, null, null)).isEqualByComparingTo("0");
    }

    @Test
    void missingConfigurationIsReportedNotDefaulted() {
        assertThatThrownBy(() -> FeeCalculator.component("stamp duty", BigDecimal.TEN, "PERCENTAGE_WITH_CAP",
                new BigDecimal("0.01"), null, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("maximum cap");
        assertThatThrownBy(() -> FeeCalculator.component("stamp duty", BigDecimal.TEN, "SLAB",
                null, null, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("unknown");
    }
}
