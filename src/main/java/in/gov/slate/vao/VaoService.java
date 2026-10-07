package in.gov.slate.vao;

import static in.gov.slate.survey.SiteVisitSupport.AGREED_DATE;
import static in.gov.slate.survey.SiteVisitSupport.AGREED_TIME;
import static in.gov.slate.survey.SiteVisitSupport.HH_MM;

import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
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
import in.gov.slate.survey.SiteVisitSupport;
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
                                  WHERE sv.transaction_id = t.id AND sv.visit_purpose = 'FIELD_VERIFICATION'
                                  ORDER BY sv.id DESC LIMIT 1) v ON TRUE
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
        this.visitSlots = SiteVisitSupport.parseSlots(visitSlots);
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
                """.formatted(AGREED_DATE, AGREED_TIME), txnParam).stream().map(SiteVisitSupport::normalise).toList());
        out.put("survey", jdbc.queryForList("""
                SELECT s.id, s.submitted_at, s.authoritative_recorded_extent, s.measured_extent, s.extent_unit,
                       s.variance_pct, s.tolerance_pct, s.within_tolerance, s.routed_to, s.fmb_sketch_reference,
                       s.survey_date, s.centroid_lat, s.centroid_lon, s.boundary_north, s.boundary_south,
                       s.boundary_east, s.boundary_west, s.site_notes, u.full_name AS submitted_by_name
                  FROM survey.submission s
                  LEFT JOIN sec.user u ON u.id = s.submitted_by
                 WHERE s.transaction_id = :txnId ORDER BY s.id DESC LIMIT 1
                """, txnParam).stream().findFirst().map(SiteVisitSupport::normalise).orElse(null));
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
        return SiteVisitSupport.slotGrid(visitSlots, visitsOn(user, date, null), date);
    }

    /**
     * Books (or, before check-in, reschedules) the VAO's own field-verification slot. The
     * Surveyor isn't involved: the slot is booked as soon as the VAO picks it. Booking opens
     * once the survey stage is over, keeping the Surveyor -> VAO -> Tahsildar sequence.
     */
    @Transactional
    public Map<String, Object> book(String txnRef, BookingRequest req) {
        CurrentUser user = requireVao();
        user.requirePermission("SURVEY_SCHEDULE");
        Map<String, Object> row = loadRecord(user, txnRef);
        String status = (String) row.get("status");
        if ("SURVEY_PENDING".equals(status)) {
            throw ApiException.conflict(
                    "The field survey is still pending; book your field-verification slot once the Surveyor submits it");
        }
        if (!"VAO_PENDING".equals(status) && !"OBJECTION_PENDING".equals(status)) {
            throw ApiException.conflict("Visit slots can't be booked once a record is " + status);
        }
        SiteVisitSupport.validateSlot(req.visitDate(), req.visitTime());
        long txnId = ((Number) row.get("transaction_id")).longValue();
        requireSlotFree(user, req.visitDate(), req.visitTime(), txnId);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("txnId", txnId)
                .addValue("userId", user.id())
                .addValue("date", req.visitDate())
                .addValue("time", req.visitTime())
                .addValue("id", row.get("visit_id"));
        String action;
        if ("ACCEPTED".equals(row.get("visit_status")) && row.get("vao_checkin_at") == null) {
            jdbc.update("""
                    UPDATE survey.site_visit SET visit_date = :date, visit_time = :time, counter_visit_date = NULL,
                           counter_visit_time = NULL, counter_by_role = NULL, vao_user_id = :userId, accepted_at = now()
                     WHERE id = :id
                    """, params);
            action = "SITE_VISIT_RESCHEDULED";
        } else if (SiteVisitSupport.slotBooked(row)) {
            throw ApiException.conflict("Your field-verification visit for this record is already attended");
        } else {
            jdbc.update("""
                    INSERT INTO survey.site_visit (state_code, transaction_id, proposed_by_role, proposed_by_user_id,
                        visit_date, visit_time, status, visit_purpose, vao_user_id, accepted_at)
                    VALUES (:stateCode, :txnId, 'VAO', :userId, :date, :time, 'ACCEPTED', 'FIELD_VERIFICATION',
                            :userId, now())
                    """, params);
            action = "SITE_VISIT_BOOKED";
        }

        claim(txnId, user);
        audit.record(action, "SITE_VISIT", txnRef, txnRef, (String) row.get("property_ref"),
                Map.of("visitDate", req.visitDate().toString(), "visitTime", req.visitTime().format(HH_MM),
                        "role", "VAO"),
                req.remarks());
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

    private void claim(long txnId, CurrentUser user) {
        jdbc.update("UPDATE core.transaction SET assigned_vao_id = COALESCE(assigned_vao_id, :userId) WHERE id = :txnId",
                new MapSqlParameterSource().addValue("txnId", txnId).addValue("userId", user.id()));
    }

    private void requireSlotFree(CurrentUser user, LocalDate date, LocalTime time, long ownTxnId) {
        boolean clash = visitsOn(user, date, ownTxnId).stream()
                .anyMatch(v -> ((Time) v.get("agreed_time")).toLocalTime().equals(time));
        if (clash) {
            throw ApiException.conflict("You already have a site visit booked at " + time.format(HH_MM) + " on " + date);
        }
    }

    /** The VAO's booked field-verification visits on a date, optionally excluding one transaction. */
    private List<Map<String, Object>> visitsOn(CurrentUser user, LocalDate date, Long excludeTxnId) {
        MapSqlParameterSource params = scope(user).addValue("date", date)
                .addValue("exclude", excludeTxnId == null ? -1L : excludeTxnId);
        return jdbc.queryForList("""
                SELECT v.id, v.status, t.txn_ref, p.ulpin, %s AS agreed_time
                  FROM survey.site_visit v
                  JOIN core.transaction t ON t.id = v.transaction_id
                  JOIN core.property p ON p.id = t.property_id
                 WHERE t.state_code = :stateCode AND t.id <> :exclude
                   AND v.visit_purpose = 'FIELD_VERIFICATION' AND v.status = 'ACCEPTED'
                   AND v.vao_checkin_at IS NULL
                   AND %s = :date AND %s IS NOT NULL
                   AND (v.vao_user_id = :userId OR t.assigned_vao_id = :userId)
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
                 WHERE v.id = :id AND v.transaction_id = :txnId AND v.visit_purpose = 'FIELD_VERIFICATION'
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
        Map<String, Object> row = SiteVisitSupport.normalise(raw);
        VisitStage stage = VisitStage.of(row, today);
        boolean booked = SiteVisitSupport.slotBooked(row);
        row.put("stage", stage.name());
        row.put("stage_label", stage.label());
        row.put("action_required", stage.actionRequired());
        row.put("slot_booked", booked);
        row.put("can_verify", booked && "VAO_PENDING".equals(row.get("status"))
                && "VAO_PENDING".equals(row.get("mutation_status")));
        return row;
    }
}
