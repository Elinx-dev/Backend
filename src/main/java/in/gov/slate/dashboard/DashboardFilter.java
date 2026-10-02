package in.gov.slate.dashboard;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import in.gov.slate.common.ApiException;

/**
 * A validated admin dashboard query: an optional state and a half-open time
 * window {@code [from, to)} resolved in the dashboard time zone.
 */
public record DashboardFilter(String stateCode, Period period, LocalDate fromDate, LocalDate toDate,
                              OffsetDateTime from, OffsetDateTime to, Bucket bucket) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final String ALL_STATES = "ALL";
    public static final int MAX_RANGE_DAYS = 366;

    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");

    public enum Period { TODAY, WEEK, MONTH, CUSTOM }

    /** Chart granularity; the SQL unit is a fixed literal, never caller input. */
    public enum Bucket {
        HOUR("hour", "1 hour"), DAY("day", "1 day");

        private final String unit;
        private final String step;

        Bucket(String unit, String step) {
            this.unit = unit;
            this.step = step;
        }

        public String unit() {
            return unit;
        }

        public String step() {
            return step;
        }
    }

    public boolean allStates() {
        return stateCode == null;
    }

    public static DashboardFilter of(String state, String period, String from, String to, LocalDate today) {
        String stateCode = normaliseState(state);
        Period p = parsePeriod(period);
        LocalDate start;
        LocalDate end;
        switch (p) {
            case TODAY -> {
                start = today;
                end = today;
            }
            case WEEK -> {
                start = today.minusDays(6);
                end = today;
            }
            case MONTH -> {
                start = today.minusDays(29);
                end = today;
            }
            default -> {
                start = parseDate("from", from);
                end = parseDate("to", to);
                if (end.isBefore(start)) {
                    throw bad("INVALID_RANGE", "'from' must be on or before 'to'");
                }
                if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
                    throw bad("INVALID_RANGE", "Date range cannot exceed " + MAX_RANGE_DAYS + " days");
                }
            }
        }
        Bucket bucket = start.equals(end) ? Bucket.HOUR : Bucket.DAY;
        return new DashboardFilter(stateCode, p, start, end,
                start.atStartOfDay(ZONE).toOffsetDateTime(),
                end.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime(), bucket);
    }

    private static String normaliseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String value = state.trim().toUpperCase(Locale.ROOT);
        if (ALL_STATES.equals(value)) {
            return null;
        }
        if (!STATE.matcher(value).matches()) {
            throw bad("INVALID_STATE", "Unknown state code " + state);
        }
        return value;
    }

    private static Period parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            return Period.MONTH;
        }
        try {
            return Period.valueOf(period.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw bad("INVALID_PERIOD", "Period must be one of TODAY, WEEK, MONTH or CUSTOM");
        }
    }

    private static LocalDate parseDate(String name, String value) {
        if (value == null || value.isBlank()) {
            throw bad("INVALID_RANGE", "'" + name + "' is required for a custom date range");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw bad("INVALID_RANGE", "'" + name + "' must be a date in YYYY-MM-DD format");
        }
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
