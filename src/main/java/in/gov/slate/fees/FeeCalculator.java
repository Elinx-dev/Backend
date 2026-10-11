package in.gov.slate.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;

import in.gov.slate.common.ApiException;

/** Fee Master arithmetic: no rates, caps or amounts live here, only how a configured row is applied. */
final class FeeCalculator {

    static final String PERCENTAGE = "PERCENTAGE";
    static final String PERCENTAGE_WITH_CAP = "PERCENTAGE_WITH_CAP";
    static final String FIXED = "FIXED";

    private FeeCalculator() {
    }

    /** One component (stamp duty or registration fee) as configured by its calculation type. */
    static BigDecimal component(String label, BigDecimal value, String calcType, BigDecimal rate, BigDecimal fixed,
                                BigDecimal min, BigDecimal cap) {
        BigDecimal amount = switch (calcType == null ? "" : calcType) {
            case FIXED -> require(fixed, label + " fixed amount");
            case PERCENTAGE -> percentage(value, require(rate, label + " rate"));
            case PERCENTAGE_WITH_CAP -> percentage(value, require(rate, label + " rate"))
                    .min(require(cap, label + " maximum cap"));
            default -> throw ApiException.conflict("Fee Master has unknown " + label + " calculation type " + calcType);
        };
        if (!FIXED.equals(calcType) && min != null && amount.compareTo(min) < 0) {
            amount = min;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentage(BigDecimal value, BigDecimal rate) {
        return value.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal require(BigDecimal value, String what) {
        if (value == null) {
            throw ApiException.conflict("Fee Master is missing the " + what);
        }
        return value;
    }
}
