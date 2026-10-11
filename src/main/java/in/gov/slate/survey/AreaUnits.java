package in.gov.slate.survey;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

import in.gov.slate.common.ApiException;

/** Area units used for land extents, so a measured extent can be compared with the recorded one. */
public final class AreaUnits {

    private static final Map<String, BigDecimal> SQ_METRES = Map.of(
            "SQ_FT", new BigDecimal("0.09290304"),
            "SQ_M", BigDecimal.ONE,
            "CENT", new BigDecimal("40.468564224"),
            "ACRE", new BigDecimal("4046.8564224"),
            "HECTARE", new BigDecimal("10000"));

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("SQFT", "SQ_FT"), Map.entry("SQ.FT", "SQ_FT"), Map.entry("SQ_FT", "SQ_FT"),
            Map.entry("SQUARE_FEET", "SQ_FT"), Map.entry("SFT", "SQ_FT"),
            Map.entry("SQM", "SQ_M"), Map.entry("SQ.M", "SQ_M"), Map.entry("SQ_M", "SQ_M"),
            Map.entry("SQUARE_METRES", "SQ_M"), Map.entry("SQUARE_METERS", "SQ_M"),
            Map.entry("CENT", "CENT"), Map.entry("CENTS", "CENT"),
            Map.entry("ACRE", "ACRE"), Map.entry("ACRES", "ACRE"), Map.entry("AC", "ACRE"),
            Map.entry("HECTARE", "HECTARE"), Map.entry("HECTARES", "HECTARE"), Map.entry("HA", "HECTARE"));

    private AreaUnits() {
    }

    /** Canonical code for a unit label such as "Sq.ft" or "Acres"; unknown labels are upper-cased as is. */
    public static String code(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        String key = unit.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return ALIASES.getOrDefault(key, key);
    }

    public static BigDecimal convert(BigDecimal value, String fromUnit, String toUnit) {
        String from = code(fromUnit);
        String to = code(toUnit);
        if (from == null || to == null || from.equals(to)) {
            return value;
        }
        BigDecimal fromFactor = SQ_METRES.get(from);
        BigDecimal toFactor = SQ_METRES.get(to);
        if (fromFactor == null || toFactor == null) {
            throw ApiException.badRequest("Cannot compare an extent in " + fromUnit + " with one in " + toUnit);
        }
        return value.multiply(fromFactor).divide(toFactor, MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_UP);
    }
}
