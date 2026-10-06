package in.gov.slate.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

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

    record OtherCharges(BigDecimal total, List<Map<String, Object>> lines, boolean needsPageCount) {
    }

    /** Configured other charges: each entry is FLAT or PER_PAGE (amount × number of pages). */
    static OtherCharges otherCharges(JsonNode charges, Integer pageCount) {
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean needsPageCount = false;
        if (charges != null && charges.isArray()) {
            for (JsonNode charge : charges) {
                BigDecimal amount = new BigDecimal(charge.path("amount").asText("0"));
                String basis = charge.path("basis").asText("FLAT");
                BigDecimal lineTotal;
                if ("PER_PAGE".equals(basis)) {
                    needsPageCount = true;
                    if (pageCount == null || pageCount <= 0) {
                        throw ApiException.badRequest("Enter the number of pages; "
                                + charge.path("label").asText("a charge") + " is charged per page");
                    }
                    lineTotal = amount.multiply(BigDecimal.valueOf(pageCount));
                } else if ("FLAT".equals(basis)) {
                    lineTotal = amount;
                } else {
                    throw ApiException.conflict("Fee Master other charge has unknown basis " + basis);
                }
                lineTotal = lineTotal.setScale(2, RoundingMode.HALF_UP);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("code", charge.path("code").asText());
                line.put("label", charge.path("label").asText(charge.path("code").asText()));
                line.put("basis", basis);
                line.put("rate", amount);
                line.put("pages", "PER_PAGE".equals(basis) ? pageCount : null);
                line.put("amount", lineTotal);
                lines.add(line);
                total = total.add(lineTotal);
            }
        }
        return new OtherCharges(total.setScale(2, RoundingMode.HALF_UP), lines, needsPageCount);
    }
}
