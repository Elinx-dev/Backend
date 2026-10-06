package in.gov.slate.vao;

import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import jakarta.validation.constraints.NotNull;

/**
 * Records assigned to the signed-in VAO and their site-visit slots. A record is the
 * VAO's when it is assigned to them, or unassigned and located in one of their villages.
 */
@Service
public class VaoService {

    public record BookingRequest(@NotNull LocalDate visitDate, @NotNull LocalTime visitTime, String remarks) {
    }

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> OPEN_VISIT_STATUSES = List.of("PROPOSED", "COUNTER_PROPOSED");
    private static final LocalTime EARLIEST_SLOT = LocalTime.of(8, 0);
    private static final LocalTime LATEST_SLOT = LocalTime.of(18, 0);

    private static final String AGREED_DATE = "COALESCE(v.counter_visit_date, v.visit_date)";
    private static final String AGREED_TIME =
            "CASE WHEN v.counter_visit_date IS NOT NULL THEN v.counter_visit_time ELSE v.visit_time END";

    private static final String RECORD_SELECT = """
            SELECT t.id AS transaction_id, t.txn_ref, t.deed_type_code, t.status, t.current_stage_code,
                   t.survey_required, t.declared_consideration, t.guideline_value, t.initiated_at, t.registered_at,
                   t.assigned_vao_id, COALESCE(vu.full_name, :fullName) AS assigned_vao_name,
                   t.assigned_surveyor_id, su.full_name AS surveyor_name,
                   p.property_ref, p.ulpin, p.survey_no, p.subdivision_no, p.village_code,
                   p.extent_value, p.extent_unit,
                   (SELECT rv.village_name FROM master.revenue_village rv
                     WHERE rv.village_code = p.village_code ORDER BY rv.id LIMIT 1) AS village_name,
                   (SELECT string_agg(tp.name, ', ' ORDER BY tp.seq) FROM core.transaction_party tp
                     WHERE tp.transaction_id = t.id AND tp.side = 'SIDE_1') AS first_parties,
                   (SELECT string_agg(tp.name, ', ' ORDER BY tp.seq) FROM core.transaction_party tp
                     WHERE tp.transaction_id = t.id AND tp.side = 'SIDE_2') AS second_parties,
                   m.id AS mutation_id, m.status AS mutation_status, m.mutation_type,
                   v.id AS visit_id, v.visit_purpose, v.status AS visit_status, v.proposed_by_role,
                   v.counter_by_role, v.visit_date, v.visit_time, v.counter_visit_date, v.counter_visit_time,
                   %s AS agreed_date, %s AS agreed_time,
                   v.accepted_at, v.vao_checkin_at, v.surveyor_checkin_at
              FROM core.transaction t
              JOIN core.property p ON p.id = t.property_id
              LEFT JOIN sec.user su ON su.id = t.assigned_surveyor_id
              LEFT JOIN sec.user vu ON vu.id = t.assigned_vao_id
              LEFT JOIN LATERAL (SELECT pm.* FROM revenue.proposed_mutation pm
                                  WHERE pm.transaction_id = t.id ORDER BY pm.id DESC LIMIT 1) m ON TRUE
              LEFT JOIN LATERAL (SELECT sv.* FROM survey.site_visit sv
                                  WHERE sv.transaction_id = t.id ORDER BY sv.id DESC LIMIT 1) v ON TRUE
             WHERE t.state_code = :stateCode
               AND t.status IN ('SURVEY_PENDING','VAO_PENDING','OBJECTION_PENDING','TAHSILDAR_PENDING','REVENUE_APPROVED')
               AND (t.assigned_vao_id = :userId
                    OR (t.assigned_vao_id IS NULL AND p.village_code IN (:villages)))
            """.formatted(AGREED_DATE, AGREED_TIME);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final AuditService audit;
    private final List<LocalTime> visitSlots;

    public VaoService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository, AuditService audit,
                      @Value("${slate.vao.visit-slots:09:00,10:30,12:00,14:30,16:00}") String visitSlots) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.audit = audit;
        this.visitSlots = Arrays.stream(visitSlots.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(LocalTime::parse).sorted().toList();
    }

    public List<Map<String, Object>> records() {
        CurrentUser user = requireVao();
        List<Map<String, Object>> rows = jdbc.queryForList(
                RECORD_SELECT + " ORDER BY " + AGREED_DATE + " NULLS LAST, t.registered_at DESC NULLS LAST, t.id DESC",
                scope(user));
        LocalDate today = LocalDate.now();
        return rows.stream().map(r -> enrich(r, today)).toList();
    }

    public Map<String, Object> dashboard() {
        CurrentUser user = requireVao();
        List<Map<String, Object>> records = records();
        LocalDate today = LocalDate.now();
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("assigned", records.size());
        kpis.put("actionRequired", records.stream().filter(r -> Boolean.TRUE.equals(r.get("action_required"))).count());
        kpis.put("scheduled", records.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("slot_booked")) && r.get("vao_checkin_at") == null
                        && !"VERIFIED".equals(r.get("stage"))).count());
        kpis.put("visitDone", records.stream()
                .filter(r -> r.get("vao_checkin_at") != null && !"VERIFIED".equals(r.get("stage"))).count());
        kpis.put("readyToVerify", records.stream().filter(r -> "READY_TO_VERIFY".equals(r.get("stage"))).count());
        kpis.put("verified", records.stream().filter(r -> "VERIFIED".equals(r.get("stage"))).count());

        Map<String, Long> byStage = new LinkedHashMap<>();
        for (VisitStage stage : VisitStage.values()) {
            byStage.put(stage.name(), records.stream().filter(r -> stage.name().equals(r.get("stage"))).count());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("officer", Map.of("id", user.id(), "name", user.fullName(), "username", user.username(),
                "villages", List.copyOf(user.villageCodes())));
        out.put("today", today.toString());
        out.put("kpis", kpis);
        out.put("byStage", byStage);
        out.put("todaysVisits", records.stream().filter(r -> today.toString().equals(r.get("agreed_date"))).toList());
        out.put("records", records);
        return out;
    }

    public Map<String, Object> record(String txnRef) {
        CurrentUser user = requireVao();
        Map<String, Object> row = loadRecord(user, txnRef);
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        var txnParam = new MapSqlParameterSource("txnId", ctx.id());

        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("property", ctx.property());
        out.put("parties", ctx.parties());
        out.put("visits", jdbc.queryForList("""
                SELECT v.*, %s AS agreed_date, %s AS agreed_time, pu.full_name AS proposed_by_name
                  FROM survey.site_visit v
                  LEFT JOIN sec.user pu ON pu.id = v.proposed_by_user_id
                 WHERE v.transaction_id = :txnId ORDER BY v.id DESC
                """.formatted(AGREED_DATE, AGREED_TIME), txnParam).stream().map(VaoService::normalise).toList());
        out.put("survey", jdbc.queryForList("""
                SELECT s.id, s.submitted_at, s.authoritative_recorded_extent, s.measured_extent, s.extent_unit,
                       s.variance_pct, s.tolerance_pct, s.within_tolerance, s.routed_to, s.fmb_sketch_reference,
                       s.survey_date, s.centroid_lat, s.centroid_lon, s.boundary_north, s.boundary_south,
                       s.boundary_east, s.boundary_west, s.site_notes, u.full_name AS submitted_by_name
                  FROM survey.submission s
                  LEFT JOIN sec.user u ON u.id = s.submitted_by
                 WHERE s.transaction_id = :txnId ORDER BY s.id DESC LIMIT 1
                """, txnParam).stream().findFirst().map(VaoService::normalise).orElse(null));
        if (row.get("mutation_id") != null) {
            var mutationParam = new MapSqlParameterSource("id", row.get("mutation_id"));
            out.put("mutation", jdbc.queryForList(
                    "SELECT * FROM revenue.proposed_mutation WHERE id = :id", mutationParam).get(0));
            out.put("objections", jdbc.queryForList(
                    "SELECT * FROM revenue.objection WHERE mutation_id = :id ORDER BY id", mutationParam));
        }
        return out;
    }

    /** The VAO's slot grid for a date; a slot is taken when any of the VAO's visits is planned at that time. */
    public Map<String, Object> slots(LocalDate date) {
        CurrentUser user = requireVao();
        Map<LocalTime, Map<String, Object>> taken = new LinkedHashMap<>();
        for (Map<String, Object> visit : visitsOn(user, date, null)) {
            taken.put(((Time) visit.get("agreed_time")).toLocalTime(), visit);
        }
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (LocalTime slot : visitSlots) {
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

    /**
     * Books a site-visit slot. While a survey is pending this negotiates the joint visit with
     * the Surveyor (propose, counter or accept); once the record is with the VAO it books a
     * field-verification visit directly.
     */
    @Transactional
    public Map<String, Object> book(String txnRef, BookingRequest req) {
        CurrentUser user = requireVao();
        user.requirePermission("SURVEY_SCHEDULE");
        Map<String, Object> row = loadRecord(user, txnRef);
        validateSlot(req.visitDate(), req.visitTime());
        long txnId = ((Number) row.get("transaction_id")).longValue();
        requireSlotFree(user, req.visitDate(), req.visitTime(), txnId);

        String status = (String) row.get("status");
        String visitStatus = (String) row.get("visit_status");
        Long visitId = row.get("visit_id") == null ? null : ((Number) row.get("visit_id")).longValue();
        var params = new MapSqlParameterSource()
                .addValue("txnId", txnId).addValue("userId", user.id()).addValue("stateCode", user.stateCode())
                .addValue("date", req.visitDate()).addValue("time", req.visitTime()).addValue("id", visitId);
        String action;

        if ("SURVEY_PENDING".equals(status)) {
            if (visitStatus != null && VisitStage.BOOKED_VISIT_STATUSES.contains(visitStatus)) {
                throw ApiException.conflict("The joint site visit is already booked for this record");
            }
            if (visitStatus != null && OPEN_VISIT_STATUSES.contains(visitStatus)) {
                boolean sameSlot = req.visitDate().toString().equals(row.get("agreed_date"))
                        && req.visitTime().format(HH_MM).equals(row.get("agreed_time"));
                if ("SURVEYOR".equals(VisitStage.lastMover(row)) && sameSlot) {
                    acceptVisit(params);
                    action = "SITE_VISIT_ACCEPTED";
                } else if ("SURVEYOR".equals(VisitStage.lastMover(row))
                        || "COUNTER_PROPOSED".equals(visitStatus)) {
                    jdbc.update("""
                            UPDATE survey.site_visit SET counter_visit_date = :date, counter_visit_time = :time,
                                   counter_by_role = 'VAO', status = 'COUNTER_PROPOSED', vao_user_id = :userId
                             WHERE id = :id
                            """, params);
                    action = "SITE_VISIT_COUNTER_PROPOSED";
                } else {
                    jdbc.update("""
                            UPDATE survey.site_visit SET visit_date = :date, visit_time = :time, vao_user_id = :userId
                             WHERE id = :id
                            """, params);
                    action = "SITE_VISIT_PROPOSED";
                }
            } else {
                jdbc.update("""
                        INSERT INTO survey.site_visit (state_code, transaction_id, proposed_by_role, proposed_by_user_id,
                            visit_date, visit_time, status, visit_purpose, vao_user_id)
                        VALUES (:stateCode, :txnId, 'VAO', :userId, :date, :time, 'PROPOSED', 'JOINT_SURVEY', :userId)
                        """, params);
                action = "SITE_VISIT_PROPOSED";
            }
        } else if ("VAO_PENDING".equals(status) || "OBJECTION_PENDING".equals(status)) {
            boolean reschedulable = "ACCEPTED".equals(visitStatus) && row.get("vao_checkin_at") == null
                    && "FIELD_VERIFICATION".equals(row.get("visit_purpose"));
            if (reschedulable) {
                jdbc.update("""
                        UPDATE survey.site_visit SET visit_date = :date, visit_time = :time,
                               counter_visit_date = NULL, counter_visit_time = NULL, counter_by_role = NULL,
                               vao_user_id = :userId, accepted_at = now()
                         WHERE id = :id
                        """, params);
                action = "SITE_VISIT_RESCHEDULED";
            } else if (visitStatus != null && VisitStage.BOOKED_VISIT_STATUSES.contains(visitStatus)) {
                throw ApiException.conflict("A site visit is already booked and attended for this record");
            } else {
                jdbc.update("""
                        INSERT INTO survey.site_visit (state_code, transaction_id, proposed_by_role, proposed_by_user_id,
                            visit_date, visit_time, status, visit_purpose, vao_user_id, accepted_at)
                        VALUES (:stateCode, :txnId, 'VAO', :userId, :date, :time, 'ACCEPTED', 'FIELD_VERIFICATION',
                                :userId, now())
                        """, params);
                action = "SITE_VISIT_BOOKED";
            }
        } else {
            throw ApiException.conflict("Visit slots can't be booked once a record is " + status);
        }

        claim(txnId, user);
        audit.record(action, "SITE_VISIT", txnRef, txnRef, (String) row.get("property_ref"),
                Map.of("visitDate", req.visitDate().toString(), "visitTime", req.visitTime().format(HH_MM)),
                req.remarks());
        return loadRecord(user, txnRef);
    }

    /** Accepts the Surveyor's proposed (or counter-proposed) date as the booked slot. */
    @Transactional
    public Map<String, Object> accept(String txnRef, long visitId) {
        CurrentUser user = requireVao();
        user.requirePermission("SURVEY_SCHEDULE");
        Map<String, Object> row = loadRecord(user, txnRef);
        Map<String, Object> visit = requireVisit(row, visitId);
        if (!OPEN_VISIT_STATUSES.contains((String) visit.get("status"))) {
            throw ApiException.conflict("Only a proposed visit can be accepted");
        }
        String lastMover = "COUNTER_PROPOSED".equals(visit.get("status"))
                ? (String) visit.get("counter_by_role") : (String) visit.get("proposed_by_role");
        if ("VAO".equals(lastMover)) {
            throw ApiException.conflict("Waiting for the Surveyor to respond to your proposal");
        }
        long txnId = ((Number) row.get("transaction_id")).longValue();
        requireSlotFree(user, LocalDate.parse(visit.get("agreed_date").toString()),
                ((Time) visit.get("agreed_time")).toLocalTime(), txnId);
        acceptVisit(new MapSqlParameterSource().addValue("id", visitId).addValue("userId", user.id()));
        claim(txnId, user);
        audit.record("SITE_VISIT_ACCEPTED", "SITE_VISIT", String.valueOf(visitId), txnRef,
                (String) row.get("property_ref"), Map.of("visitId", visitId), null);
        return loadRecord(user, txnRef);
    }

    /** Check-in time is taken from the server clock; it is never entered by the officer. */
    @Transactional
    public Map<String, Object> checkIn(String txnRef, long visitId) {
        CurrentUser user = requireVao();
        user.requirePermission("SURVEY_SCHEDULE");
        Map<String, Object> row = loadRecord(user, txnRef);
        Map<String, Object> visit = requireVisit(row, visitId);
        if (!"ACCEPTED".equals(visit.get("status"))) {
            throw ApiException.conflict("Book the visit slot before checking in");
        }
        if (LocalDate.parse(visit.get("agreed_date").toString()).isAfter(LocalDate.now())) {
            throw ApiException.conflict("Check-in opens on the booked visit date");
        }
        jdbc.update("UPDATE survey.site_visit SET vao_checkin_at = COALESCE(vao_checkin_at, now()) WHERE id = :id",
                new MapSqlParameterSource("id", visitId));
        audit.record("SITE_VISIT_CHECKIN", "SITE_VISIT", String.valueOf(visitId), txnRef,
                (String) row.get("property_ref"), Map.of("role", "VAO"), null);
        return loadRecord(user, txnRef);
    }

    private void acceptVisit(MapSqlParameterSource params) {
        jdbc.update("""
                UPDATE survey.site_visit SET status = 'ACCEPTED', accepted_at = now(), vao_user_id = :userId
                 WHERE id = :id
                """, params);
    }

    private void claim(long txnId, CurrentUser user) {
        jdbc.update("UPDATE core.transaction SET assigned_vao_id = COALESCE(assigned_vao_id, :userId) WHERE id = :txnId",
                new MapSqlParameterSource().addValue("txnId", txnId).addValue("userId", user.id()));
    }

    private void validateSlot(LocalDate date, LocalTime time) {
        if (date.isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Choose today or a later date for the visit");
        }
        if (time.isBefore(EARLIEST_SLOT) || time.isAfter(LATEST_SLOT)) {
            throw ApiException.badRequest("Site visits are scheduled between 08:00 and 18:00");
        }
    }

    private void requireSlotFree(CurrentUser user, LocalDate date, LocalTime time, long ownTxnId) {
        boolean clash = visitsOn(user, date, ownTxnId).stream()
                .anyMatch(v -> ((Time) v.get("agreed_time")).toLocalTime().equals(time));
        if (clash) {
            throw ApiException.conflict("You already have a site visit booked at " + time.format(HH_MM) + " on " + date);
        }
    }

    /** Open and booked visits on a date across the VAO's records, optionally excluding one transaction. */
    private List<Map<String, Object>> visitsOn(CurrentUser user, LocalDate date, Long excludeTxnId) {
        MapSqlParameterSource params = scope(user).addValue("date", date)
                .addValue("exclude", excludeTxnId == null ? -1L : excludeTxnId);
        return jdbc.queryForList("""
                SELECT v.id, v.status, t.txn_ref, p.ulpin, %s AS agreed_time
                  FROM survey.site_visit v
                  JOIN core.transaction t ON t.id = v.transaction_id
                  JOIN core.property p ON p.id = t.property_id
                 WHERE t.state_code = :stateCode AND t.id <> :exclude
                   AND v.status IN ('PROPOSED','COUNTER_PROPOSED','ACCEPTED')
                   AND v.vao_checkin_at IS NULL
                   AND %s = :date AND %s IS NOT NULL
                   AND (v.vao_user_id = :userId OR t.assigned_vao_id = :userId
                        OR (t.assigned_vao_id IS NULL AND p.village_code IN (:villages)))
                """.formatted(AGREED_TIME, AGREED_DATE, AGREED_TIME), params);
    }

    private Map<String, Object> loadRecord(CurrentUser user, String txnRef) {
        var rows = jdbc.queryForList(RECORD_SELECT + " AND t.txn_ref = :txnRef",
                scope(user).addValue("txnRef", txnRef));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Record " + txnRef + " assigned to you");
        }
        return enrich(rows.get(0), LocalDate.now());
    }

    private Map<String, Object> requireVisit(Map<String, Object> row, long visitId) {
        var rows = jdbc.queryForList("""
                SELECT v.*, %s AS agreed_date, %s AS agreed_time FROM survey.site_visit v
                 WHERE v.id = :id AND v.transaction_id = :txnId
                """.formatted(AGREED_DATE, AGREED_TIME),
                new MapSqlParameterSource().addValue("id", visitId).addValue("txnId", row.get("transaction_id")));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Site visit " + visitId);
        }
        return rows.get(0);
    }

    private static MapSqlParameterSource scope(CurrentUser user) {
        List<String> villages = user.villageCodes().isEmpty() ? List.of("__NONE__") : List.copyOf(user.villageCodes());
        return new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("userId", user.id())
                .addValue("fullName", user.fullName())
                .addValue("villages", villages);
    }

    private static CurrentUser requireVao() {
        CurrentUser user = CurrentUser.require();
        if (!user.hasRole("VAO")) {
            throw ApiException.forbidden("The VAO portal is only available to Village Administrative Officers");
        }
        user.requirePermission("TXN_READ");
        return user;
    }

    static Map<String, Object> enrich(Map<String, Object> raw, LocalDate today) {
        Map<String, Object> row = normalise(raw);
        VisitStage stage = VisitStage.of(row, today);
        boolean booked = VisitStage.slotBooked(row);
        row.put("stage", stage.name());
        row.put("stage_label", stage.label());
        row.put("action_required", stage.actionRequired());
        row.put("slot_booked", booked);
        row.put("can_verify", booked && "VAO_PENDING".equals(row.get("status"))
                && "VAO_PENDING".equals(row.get("mutation_status")));
        return row;
    }

    /** Renders SQL dates as yyyy-MM-dd and times as HH:mm so the UI never has to reformat them. */
    static Map<String, Object> normalise(Map<String, Object> raw) {
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
