package in.gov.slate.surveyor;

import static in.gov.slate.survey.SiteVisitSupport.AGREED_DATE;
import static in.gov.slate.survey.SiteVisitSupport.AGREED_TIME;
import static in.gov.slate.survey.SiteVisitSupport.HH_MM;

import java.math.BigDecimal;
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
import in.gov.slate.survey.SurveyService;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.vao.VaoService;

/**
 * Records assigned to the signed-in Surveyor, the Surveyor's own site-visit slots and the
 * field survey. A record is the Surveyor's when it is assigned to them, unassigned and in
 * one of their villages, or when they submitted its survey.
 */
@Service
public class SurveyorService {

    private static final String RECORD_SELECT = """
            SELECT t.id AS transaction_id, t.txn_ref, t.deed_type_code, t.status, t.current_stage_code,
                   t.survey_required, t.subdivision_required, t.declared_consideration, t.registered_at,
                   t.assigned_surveyor_id, COALESCE(su.full_name, :fullName) AS surveyor_name,
                   t.assigned_vao_id, vu.full_name AS assigned_vao_name,
                   p.property_ref, p.ulpin, p.survey_no, p.subdivision_no, p.village_code,
                   p.extent_value, p.extent_unit,
                   (SELECT rv.village_name FROM master.revenue_village rv
                     WHERE rv.village_code = p.village_code ORDER BY rv.id LIMIT 1) AS village_name,
                   (SELECT string_agg(tp.name, ', ' ORDER BY tp.seq) FROM core.transaction_party tp
                     WHERE tp.transaction_id = t.id AND tp.side = 'SIDE_1') AS first_parties,
                   (SELECT string_agg(tp.name, ', ' ORDER BY tp.seq) FROM core.transaction_party tp
                     WHERE tp.transaction_id = t.id AND tp.side = 'SIDE_2') AS second_parties,
                   m.id AS mutation_id, m.status AS mutation_status,
                   v.id AS visit_id, v.visit_purpose, v.status AS visit_status, v.proposed_by_role,
                   v.counter_by_role, v.visit_date, v.visit_time, v.counter_visit_date, v.counter_visit_time,
                   %s AS agreed_date, %s AS agreed_time,
                   v.accepted_at, v.vao_checkin_at, v.surveyor_checkin_at,
                   s.id AS submission_id, s.submitted_at, s.measured_extent, s.extent_unit AS measured_unit,
                   s.measured_extent_in_record_unit, s.variance_pct, s.within_tolerance, s.routed_to
              FROM core.transaction t
              JOIN core.property p ON p.id = t.property_id
              LEFT JOIN sec.user su ON su.id = t.assigned_surveyor_id
              LEFT JOIN sec.user vu ON vu.id = t.assigned_vao_id
              LEFT JOIN LATERAL (SELECT pm.* FROM revenue.proposed_mutation pm
                                  WHERE pm.transaction_id = t.id ORDER BY pm.id DESC LIMIT 1) m ON TRUE
              LEFT JOIN LATERAL (SELECT sv.* FROM survey.site_visit sv
                                  WHERE sv.transaction_id = t.id AND sv.visit_purpose = 'FIELD_SURVEY'
                                  ORDER BY sv.id DESC LIMIT 1) v ON TRUE
              LEFT JOIN LATERAL (SELECT ss.* FROM survey.submission ss
                                  WHERE ss.transaction_id = t.id ORDER BY ss.id DESC LIMIT 1) s ON TRUE
             WHERE t.state_code = :stateCode
               AND t.status IN ('SURVEY_PENDING','VAO_PENDING','OBJECTION_PENDING','TAHSILDAR_PENDING','REVENUE_APPROVED')
               AND (t.status = 'SURVEY_PENDING' OR s.id IS NOT NULL)
               AND (t.assigned_surveyor_id = :userId
                    OR s.submitted_by = :userId
                    OR (t.assigned_surveyor_id IS NULL AND t.status = 'SURVEY_PENDING'
                        AND p.village_code IN (:villages)))
            """.formatted(AGREED_DATE, AGREED_TIME);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final SurveyService survey;
    private final AuditService audit;
    private final List<LocalTime> visitSlots;
    private final BigDecimal tolerancePct;

    public SurveyorService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository, SurveyService survey,
                           AuditService audit,
                           @Value("${slate.vao.visit-slots:09:00,10:30,12:00,14:30,16:00}") String visitSlots,
                           @Value("${slate.survey.tolerance-pct}") BigDecimal tolerancePct) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.survey = survey;
        this.audit = audit;
        this.visitSlots = SiteVisitSupport.parseSlots(visitSlots);
        this.tolerancePct = tolerancePct;
    }

    public List<Map<String, Object>> records() {
        CurrentUser user = requireSurveyor();
        List<Map<String, Object>> rows = jdbc.queryForList(
                RECORD_SELECT + " ORDER BY " + AGREED_DATE + " NULLS LAST, t.registered_at DESC NULLS LAST, t.id DESC",
                scope(user));
        LocalDate today = LocalDate.now();
        return rows.stream().map(r -> enrich(r, today)).toList();
    }

    public Map<String, Object> dashboard() {
        CurrentUser user = requireSurveyor();
        List<Map<String, Object>> records = records();
        LocalDate today = LocalDate.now();
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("assigned", records.size());
        kpis.put("actionRequired", records.stream().filter(r -> Boolean.TRUE.equals(r.get("action_required"))).count());
        kpis.put("scheduled", countStages(records, SurveyorStage.SLOT_BOOKED, SurveyorStage.CHECK_IN_DUE));
        kpis.put("surveyDue", countStages(records, SurveyorStage.SURVEY_DUE, SurveyorStage.CONFLICT_FLAGGED));
        kpis.put("submitted", countStages(records, SurveyorStage.SUBMITTED, SurveyorStage.OBJECTION_PENDING));
        kpis.put("verified", countStages(records, SurveyorStage.VERIFIED));

        Map<String, Long> byStage = new LinkedHashMap<>();
        for (SurveyorStage stage : SurveyorStage.values()) {
            byStage.put(stage.name(), countStages(records, stage));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("officer", Map.of("id", user.id(), "name", user.fullName(), "username", user.username(),
                "villages", List.copyOf(user.villageCodes())));
        out.put("today", today.toString());
        out.put("tolerancePct", tolerancePct);
        out.put("kpis", kpis);
        out.put("byStage", byStage);
        out.put("todaysVisits", records.stream()
                .filter(r -> today.toString().equals(r.get("agreed_date")) && "SURVEY_PENDING".equals(r.get("status")))
                .toList());
        out.put("records", records);
        return out;
    }

    public Map<String, Object> record(String txnRef) {
        CurrentUser user = requireSurveyor();
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
        out.put("submissions", jdbc.queryForList("""
                SELECT s.id, s.submitted_at, s.survey_date, s.measured_extent, s.extent_unit,
                       s.measured_extent_in_record_unit, s.authoritative_recorded_extent, s.variance_pct,
                       s.tolerance_pct, s.within_tolerance, s.routed_to, s.centroid_lat, s.centroid_lon,
                       s.boundary_north, s.boundary_south, s.boundary_east, s.boundary_west, s.site_notes,
                       s.boundary_geojson::text AS boundary_geojson, u.full_name AS submitted_by_name
                  FROM survey.submission s
                  LEFT JOIN sec.user u ON u.id = s.submitted_by
                 WHERE s.transaction_id = :txnId ORDER BY s.id DESC
                """, txnParam).stream().map(SiteVisitSupport::normalise).toList());
        Object submissionId = row.get("submission_id");
        if (submissionId != null) {
            var subParam = new MapSqlParameterSource("id", submissionId);
            out.put("segments", jdbc.queryForList("""
                    SELECT seq, from_point, to_point, length_value, length_unit FROM survey.measurement_segment
                     WHERE submission_id = :id ORDER BY seq
                    """, subParam));
            out.put("boundary_points", jdbc.queryForList("""
                    SELECT seq, point_label, marker_status, latitude, longitude FROM survey.boundary_point
                     WHERE submission_id = :id ORDER BY seq
                    """, subParam));
        }
        if (row.get("mutation_id") != null) {
            var mutationParam = new MapSqlParameterSource("id", row.get("mutation_id"));
            out.put("mutation", jdbc.queryForList(
                    "SELECT * FROM revenue.proposed_mutation WHERE id = :id", mutationParam).get(0));
            out.put("objections", jdbc.queryForList(
                    "SELECT * FROM revenue.objection WHERE mutation_id = :id ORDER BY id", mutationParam));
        }
        return out;
    }

    /** The Surveyor's slot grid for a date; a slot is taken when one of their survey visits is booked then. */
    public Map<String, Object> slots(LocalDate date) {
        CurrentUser user = requireSurveyor();
        return SiteVisitSupport.slotGrid(visitSlots, visitsOn(user, date, null), date);
    }

    /**
     * Books (or, before check-in, reschedules) the Surveyor's own survey slot. The VAO isn't
     * involved: the slot is booked as soon as the Surveyor picks it.
     */
    @Transactional
    public Map<String, Object> book(String txnRef, VaoService.BookingRequest req) {
        CurrentUser user = requireSurveyor();
        user.requirePermission("SURVEY_SCHEDULE");
        Map<String, Object> row = loadRecord(user, txnRef);
        if (!"SURVEY_PENDING".equals(row.get("status"))) {
            throw ApiException.conflict("Visit slots can only be booked while the survey is pending");
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
        if ("ACCEPTED".equals(row.get("visit_status")) && row.get("surveyor_checkin_at") == null) {
            jdbc.update("""
                    UPDATE survey.site_visit SET visit_date = :date, visit_time = :time, counter_visit_date = NULL,
                           counter_visit_time = NULL, counter_by_role = NULL, surveyor_user_id = :userId,
                           accepted_at = now()
                     WHERE id = :id
                    """, params);
            action = "SITE_VISIT_RESCHEDULED";
        } else if (SiteVisitSupport.slotBooked(row)) {
            throw ApiException.conflict("Your survey visit for this record is already attended");
        } else {
            jdbc.update("""
                    INSERT INTO survey.site_visit (state_code, transaction_id, proposed_by_role, proposed_by_user_id,
                        visit_date, visit_time, status, visit_purpose, surveyor_user_id, accepted_at)
                    VALUES (:stateCode, :txnId, 'SURVEYOR', :userId, :date, :time, 'ACCEPTED', 'FIELD_SURVEY',
                            :userId, now())
                    """, params);
            action = "SITE_VISIT_BOOKED";
        }

        claim(txnId, user);
        audit.record(action, "SITE_VISIT", txnRef, txnRef, (String) row.get("property_ref"),
                Map.of("visitDate", req.visitDate().toString(), "visitTime", req.visitTime().format(HH_MM),
                        "role", "SURVEYOR"),
                req.remarks());
        return loadRecord(user, txnRef);
    }

    /** Check-in time is taken from the server clock; it is never entered by the officer. */
    @Transactional
    public Map<String, Object> checkIn(String txnRef, long visitId) {
        CurrentUser user = requireSurveyor();
        user.requirePermission("SURVEY_SCHEDULE");
        Map<String, Object> row = loadRecord(user, txnRef);
        Map<String, Object> visit = requireVisit(row, visitId);
        if (!"ACCEPTED".equals(visit.get("status"))) {
            throw ApiException.conflict("Book the visit slot before checking in");
        }
        if (LocalDate.parse(visit.get("agreed_date").toString()).isAfter(LocalDate.now())) {
            throw ApiException.conflict("Check-in opens on the booked visit date");
        }
        jdbc.update("""
                UPDATE survey.site_visit SET surveyor_checkin_at = COALESCE(surveyor_checkin_at, now()),
                       surveyor_user_id = COALESCE(surveyor_user_id, :userId)
                 WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", visitId).addValue("userId", user.id()));
        audit.record("SITE_VISIT_CHECKIN", "SITE_VISIT", String.valueOf(visitId), txnRef,
                (String) row.get("property_ref"), Map.of("role", "SURVEYOR"), null);
        return loadRecord(user, txnRef);
    }

    /** Saves the field survey; {@link SurveyService#submit} requires a booked visit slot. */
    @Transactional
    public Map<String, Object> submit(String txnRef, SurveyService.SubmissionRequest req) {
        CurrentUser user = requireSurveyor();
        Map<String, Object> row = loadRecord(user, txnRef);
        if (!Boolean.TRUE.equals(row.get("can_survey"))) {
            throw ApiException.conflict("Book your site-visit slot before submitting the survey");
        }
        Map<String, Object> out = new LinkedHashMap<>(survey.submit(txnRef, req));
        claim(((Number) row.get("transaction_id")).longValue(), user);
        out.put("record", loadRecord(user, txnRef));
        return out;
    }

    private void claim(long txnId, CurrentUser user) {
        jdbc.update("""
                UPDATE core.transaction SET assigned_surveyor_id = COALESCE(assigned_surveyor_id, :userId)
                 WHERE id = :txnId
                """, new MapSqlParameterSource().addValue("txnId", txnId).addValue("userId", user.id()));
    }

    private void requireSlotFree(CurrentUser user, LocalDate date, LocalTime time, long ownTxnId) {
        boolean clash = visitsOn(user, date, ownTxnId).stream()
                .anyMatch(v -> ((Time) v.get("agreed_time")).toLocalTime().equals(time));
        if (clash) {
            throw ApiException.conflict("You already have a site visit booked at " + time.format(HH_MM) + " on " + date);
        }
    }

    /** The Surveyor's booked survey visits on a date, optionally excluding one transaction. */
    private List<Map<String, Object>> visitsOn(CurrentUser user, LocalDate date, Long excludeTxnId) {
        MapSqlParameterSource params = scope(user).addValue("date", date)
                .addValue("exclude", excludeTxnId == null ? -1L : excludeTxnId);
        return jdbc.queryForList("""
                SELECT v.id, v.status, t.txn_ref, p.ulpin, %s AS agreed_time
                  FROM survey.site_visit v
                  JOIN core.transaction t ON t.id = v.transaction_id
                  JOIN core.property p ON p.id = t.property_id
                 WHERE t.state_code = :stateCode AND t.id <> :exclude
                   AND v.visit_purpose = 'FIELD_SURVEY' AND v.status = 'ACCEPTED'
                   AND v.surveyor_checkin_at IS NULL
                   AND %s = :date AND %s IS NOT NULL
                   AND (v.surveyor_user_id = :userId OR t.assigned_surveyor_id = :userId)
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
                 WHERE v.id = :id AND v.transaction_id = :txnId AND v.visit_purpose = 'FIELD_SURVEY'
                """.formatted(AGREED_DATE, AGREED_TIME),
                new MapSqlParameterSource().addValue("id", visitId).addValue("txnId", row.get("transaction_id")));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Site visit " + visitId);
        }
        return rows.get(0);
    }

    private static long countStages(List<Map<String, Object>> records, SurveyorStage... stages) {
        return records.stream().filter(r -> {
            for (SurveyorStage stage : stages) {
                if (stage.name().equals(r.get("stage"))) {
                    return true;
                }
            }
            return false;
        }).count();
    }

    private static MapSqlParameterSource scope(CurrentUser user) {
        List<String> villages = user.villageCodes().isEmpty() ? List.of("__NONE__") : List.copyOf(user.villageCodes());
        return new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("userId", user.id())
                .addValue("fullName", user.fullName())
                .addValue("villages", villages);
    }

    private static CurrentUser requireSurveyor() {
        CurrentUser user = CurrentUser.require();
        if (!user.hasRole("SURVEYOR")) {
            throw ApiException.forbidden("The Surveyor portal is only available to Surveyors");
        }
        user.requirePermission("TXN_READ");
        return user;
    }

    Map<String, Object> enrich(Map<String, Object> raw, LocalDate today) {
        Map<String, Object> row = SiteVisitSupport.normalise(raw);
        SurveyorStage stage = SurveyorStage.of(row, today);
        boolean booked = SiteVisitSupport.slotBooked(row);
        row.put("stage", stage.name());
        row.put("stage_label", stage.label());
        row.put("action_required", stage.actionRequired());
        row.put("slot_booked", booked);
        row.put("can_survey", booked && "SURVEY_PENDING".equals(row.get("status")));
        row.put("tolerance_pct", tolerancePct);
        return row;
    }
}
