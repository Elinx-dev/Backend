package in.gov.slate.survey;

import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.gov.slate.common.ApiException;

/** Site-visit helpers shared by the VAO and Surveyor portals. */
public final class SiteVisitSupport {

    public static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    public static final List<String> OPEN_VISIT_STATUSES = List.of("PROPOSED", "COUNTER_PROPOSED");
    public static final Set<String> BOOKED_VISIT_STATUSES = Set.of("ACCEPTED", "COMPLETED");

    /** The date and time both parties are currently looking at: the counter-proposal when there is one. */
    public static final String AGREED_DATE = "COALESCE(v.counter_visit_date, v.visit_date)";
    public static final String AGREED_TIME =
            "CASE WHEN v.counter_visit_date IS NOT NULL THEN v.counter_visit_time ELSE v.visit_time END";

    private static final LocalTime EARLIEST_SLOT = LocalTime.of(8, 0);
    private static final LocalTime LATEST_SLOT = LocalTime.of(18, 0);

    private SiteVisitSupport() {
    }

    public static List<LocalTime> parseSlots(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(LocalTime::parse).sorted().toList();
    }

    public static void validateSlot(LocalDate date, LocalTime time) {
        if (date.isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Choose today or a later date for the visit");
        }
        if (time.isBefore(EARLIEST_SLOT) || time.isAfter(LATEST_SLOT)) {
            throw ApiException.badRequest("Site visits are scheduled between 08:00 and 18:00");
        }
    }

    /** Whose turn it is on an open proposal: the proposer, or the role that countered it. */
    public static String lastMover(Map<String, Object> row) {
        return "COUNTER_PROPOSED".equals(row.get("visit_status"))
                ? (String) row.get("counter_by_role")
                : (String) row.get("proposed_by_role");
    }

    public static boolean slotBooked(Map<String, Object> row) {
        Object status = row.get("visit_status");
        return status != null && BOOKED_VISIT_STATUSES.contains(status.toString());
    }

    /** The configured slot times for a date, marking those already taken by one of the officer's visits. */
    public static Map<String, Object> slotGrid(List<LocalTime> slotTimes, List<Map<String, Object>> visits,
                                               LocalDate date) {
        Map<LocalTime, Map<String, Object>> taken = new LinkedHashMap<>();
        for (Map<String, Object> visit : visits) {
            taken.put(((Time) visit.get("agreed_time")).toLocalTime(), visit);
        }
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (LocalTime slot : slotTimes) {
            Map<String, Object> visit = taken.get(slot);
            boolean past = date.isBefore(today) || (date.equals(today) && slot.isBefore(now));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("time", slot.format(HH_MM));
            s.put("available", visit == null && !past);
            s.put("past", past);
            s.put("txn_ref", visit == null ? null : visit.get("txn_ref"));
            s.put("ulpin", visit == null ? null : visit.get("ulpin"));
            s.put("visit_status", visit == null ? null : visit.get("status"));
            slots.add(s);
        }
        return Map.of("date", date.toString(), "slots", slots);
    }

    /** Renders SQL dates as yyyy-MM-dd and times as HH:mm so the UI never has to reformat them. */
    public static Map<String, Object> normalise(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value instanceof java.sql.Date d) {
                row.put(key, d.toLocalDate().toString());
            } else if (value instanceof Time t) {
                row.put(key, t.toLocalTime().format(HH_MM));
            } else {
                row.put(key, value);
            }
        });
        return row;
    }
}
