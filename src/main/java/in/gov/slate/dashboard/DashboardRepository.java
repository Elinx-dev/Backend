package in.gov.slate.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregations behind the admin dashboard. Every query is bounded to
 * the filter's half-open window and, unless all states are requested, to one
 * state. Only bound parameters and fixed literals reach the SQL text.
 */
@Repository
public class DashboardRepository {

    private static final String ZONE = "'" + DashboardFilter.ZONE.getId() + "'";
    private static final List<String> RULE_ISSUES = List.of("DISCREPANCY_DETECTED", "REVIEW_REQUIRED", "NOT_CHECKED");

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> states() {
        return jdbc.queryForList("""
                SELECT state_code AS "code", state_name AS "name"
                  FROM cfg.state
                 WHERE active
                 ORDER BY state_name
                """, Map.of());
    }

    public Map<String, Object> summary(DashboardFilter f) {
        String sql = """
                SELECT
                  (SELECT count(*) FROM core.transaction t
                    WHERE t.initiated_at >= :from AND t.initiated_at < :to %1$s) AS "transactionsInitiated",
                  (SELECT count(*) FROM core.transaction t
                    WHERE t.registered_at >= :from AND t.registered_at < :to %1$s) AS "transactionsRegistered",
                  (SELECT count(*) FROM core.transaction t
                    WHERE t.withdrawn_at >= :from AND t.withdrawn_at < :to %1$s) AS "transactionsWithdrawn",
                  (SELECT count(*) FROM core.property p
                    WHERE p.created_at >= :from AND p.created_at < :to %2$s) AS "propertiesAdded",
                  (SELECT count(*) FROM revenue.approved_record a
                     JOIN revenue.proposed_mutation m ON m.id = a.mutation_id
                    WHERE a.approved_at >= :from AND a.approved_at < :to %3$s) AS "mutationsApproved",
                  (SELECT coalesce(sum(pay.amount), 0) FROM core.payment pay
                     JOIN core.transaction t ON t.id = pay.transaction_id
                    WHERE pay.status = 'SUCCESS' AND pay.paid_at >= :from AND pay.paid_at < :to %1$s) AS "feesCollected",
                  (SELECT round(CAST(avg(extract(epoch FROM t.registered_at - t.initiated_at)) / 3600 AS numeric), 1)
                     FROM core.transaction t
                    WHERE t.registered_at >= :from AND t.registered_at < :to %1$s) AS "avgHoursToRegister",
                  (SELECT count(*) FROM core.transaction t
                    WHERE t.status NOT IN ('REGISTERED', 'REVENUE_APPROVED', 'WITHDRAWN') %1$s) AS "openPipeline",
                  (SELECT count(*) FROM core.transaction t
                    WHERE t.status = 'EXCEPTION' %1$s) AS "openExceptions"
                """.formatted(state(f, "t"), state(f, "p"), state(f, "m"));
        return jdbc.queryForMap(sql, params(f));
    }

    public List<Map<String, Object>> transactionsByStatus(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT t.status AS "status", count(*) AS "count"
                  FROM core.transaction t
                 WHERE t.initiated_at >= :from AND t.initiated_at < :to %s
                 GROUP BY t.status
                 ORDER BY count(*) DESC, t.status
                """.formatted(state(f, "t")), params(f));
    }

    public List<Map<String, Object>> transactionsByDeedType(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT t.deed_type_code AS "code",
                       coalesce(max(d.name), t.deed_type_code) AS "name",
                       count(DISTINCT t.id) AS "count"
                  FROM core.transaction t
                  LEFT JOIN master.deed_type d ON d.state_code = t.state_code AND d.code = t.deed_type_code
                 WHERE t.initiated_at >= :from AND t.initiated_at < :to %s
                 GROUP BY t.deed_type_code
                 ORDER BY count(DISTINCT t.id) DESC
                 LIMIT 8
                """.formatted(state(f, "t")), params(f));
    }

    public List<Map<String, Object>> trend(DashboardFilter f) {
        String unit = "'" + f.bucket().unit() + "'";
        String sql = """
                WITH buckets AS (
                  SELECT generate_series(date_trunc(%1$s, CAST(:from AS timestamptz) AT TIME ZONE %2$s),
                                         date_trunc(%1$s, (CAST(:to AS timestamptz) - interval '1 second') AT TIME ZONE %2$s),
                                         interval '%3$s') AS bucket
                ), initiated AS (
                  SELECT date_trunc(%1$s, t.initiated_at AT TIME ZONE %2$s) AS bucket, count(*) AS n
                    FROM core.transaction t
                   WHERE t.initiated_at >= :from AND t.initiated_at < :to %4$s
                   GROUP BY 1
                ), registered AS (
                  SELECT date_trunc(%1$s, t.registered_at AT TIME ZONE %2$s) AS bucket, count(*) AS n
                    FROM core.transaction t
                   WHERE t.registered_at >= :from AND t.registered_at < :to %4$s
                   GROUP BY 1
                ), properties AS (
                  SELECT date_trunc(%1$s, p.created_at AT TIME ZONE %2$s) AS bucket, count(*) AS n
                    FROM core.property p
                   WHERE p.created_at >= :from AND p.created_at < :to %5$s
                   GROUP BY 1
                ), failures AS (
                  SELECT date_trunc(%1$s, a.occurred_at AT TIME ZONE %2$s) AS bucket, count(*) AS n
                    FROM sec.audit_log a
                   WHERE a.occurred_at >= :from AND a.occurred_at < :to %6$s
                     AND (a.to_status = 'EXCEPTION'
                          OR a.action = 'OBJECTION_RAISED'
                          OR (a.outcome = 'FAILURE' AND a.transaction_ref IS NOT NULL))
                   GROUP BY 1
                )
                SELECT to_char(b.bucket, 'YYYY-MM-DD"T"HH24:MI') AS "bucket",
                       coalesce(i.n, 0) AS "transactions",
                       coalesce(r.n, 0) AS "registered",
                       coalesce(p.n, 0) AS "properties",
                       coalesce(x.n, 0) AS "failures"
                  FROM buckets b
                  LEFT JOIN initiated i ON i.bucket = b.bucket
                  LEFT JOIN registered r ON r.bucket = b.bucket
                  LEFT JOIN properties p ON p.bucket = b.bucket
                  LEFT JOIN failures x ON x.bucket = b.bucket
                 ORDER BY b.bucket
                """.formatted(unit, ZONE, f.bucket().step(), state(f, "t"), state(f, "p"), state(f, "a"));
        return jdbc.queryForList(sql, params(f));
    }

    public List<Map<String, Object>> ruleOutcomes(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT r.engine AS "engine", r.overall_outcome AS "outcome", count(*) AS "count"
                  FROM rules.rule_check_result r
                  JOIN rules.rule_check_request q ON q.id = r.request_id
                 WHERE r.checked_at >= :from AND r.checked_at < :to %s
                 GROUP BY r.engine, r.overall_outcome
                 ORDER BY r.engine, r.overall_outcome
                """.formatted(state(f, "q")), params(f));
    }

    public List<Map<String, Object>> ruleFailureReasons(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT r.engine AS "engine", r.overall_outcome AS "outcome",
                       coalesce(r.reason_code, 'UNSPECIFIED') AS "reasonCode", count(*) AS "count"
                  FROM rules.rule_check_result r
                  JOIN rules.rule_check_request q ON q.id = r.request_id
                 WHERE r.checked_at >= :from AND r.checked_at < :to %s
                   AND r.overall_outcome IN (:ruleIssues)
                 GROUP BY r.engine, r.overall_outcome, coalesce(r.reason_code, 'UNSPECIFIED')
                 ORDER BY count(*) DESC
                 LIMIT 10
                """.formatted(state(f, "q")), params(f).addValue("ruleIssues", RULE_ISSUES));
    }

    /** Counts of every way a transaction can stall or fail in the window. */
    public Map<String, Object> failureOverview(DashboardFilter f) {
        String sql = """
                SELECT
                  (SELECT count(*) FROM rules.rule_check_result r
                     JOIN rules.rule_check_request q ON q.id = r.request_id
                    WHERE r.checked_at >= :from AND r.checked_at < :to %1$s
                      AND r.overall_outcome = 'DISCREPANCY_DETECTED') AS "ruleDiscrepancies",
                  (SELECT count(*) FROM rules.rule_check_result r
                     JOIN rules.rule_check_request q ON q.id = r.request_id
                    WHERE r.checked_at >= :from AND r.checked_at < :to %1$s
                      AND r.overall_outcome = 'REVIEW_REQUIRED') AS "ruleReviewRequired",
                  (SELECT count(*) FROM sec.audit_log a
                    WHERE a.occurred_at >= :from AND a.occurred_at < :to %2$s
                      AND a.to_status = 'EXCEPTION') AS "exceptionsRaised",
                  (SELECT count(*) FROM sec.audit_log a
                    WHERE a.occurred_at >= :from AND a.occurred_at < :to %2$s
                      AND a.outcome = 'FAILURE' AND a.transaction_ref IS NOT NULL) AS "failedActions",
                  (SELECT count(*) FROM revenue.objection o
                     JOIN revenue.proposed_mutation m ON m.id = o.mutation_id
                    WHERE o.recorded_at >= :from AND o.recorded_at < :to %3$s) AS "vaoObjections",
                  (SELECT count(*) FROM survey.submission s
                    WHERE s.submitted_at >= :from AND s.submitted_at < :to %4$s
                      AND NOT s.within_tolerance) AS "surveyOutOfTolerance",
                  (SELECT count(*) FROM core.transaction t
                    WHERE t.withdrawn_at >= :from AND t.withdrawn_at < :to %5$s) AS "withdrawn"
                """.formatted(state(f, "q"), state(f, "a"), state(f, "m"), state(f, "s"), state(f, "t"));
        return jdbc.queryForMap(sql, params(f));
    }

    public List<Map<String, Object>> failedActions(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT a.action AS "action", count(*) AS "count"
                  FROM sec.audit_log a
                 WHERE a.occurred_at >= :from AND a.occurred_at < :to %s
                   AND a.outcome = 'FAILURE' AND a.transaction_ref IS NOT NULL
                 GROUP BY a.action
                 ORDER BY count(*) DESC
                 LIMIT 8
                """.formatted(state(f, "a")), params(f));
    }

    public List<Map<String, Object>> mutationsByStatus(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT m.status AS "status", count(*) AS "count"
                  FROM revenue.proposed_mutation m
                 WHERE m.created_at >= :from AND m.created_at < :to %s
                 GROUP BY m.status
                """.formatted(state(f, "m")), params(f));
    }

    public Map<String, Object> vaoActivity(DashboardFilter f) {
        String sql = """
                SELECT
                  (SELECT count(*) FROM revenue.proposed_mutation m
                    WHERE m.vao_verified_at >= :from AND m.vao_verified_at < :to %1$s) AS "vaoVerified",
                  (SELECT count(*) FROM revenue.objection o
                     JOIN revenue.proposed_mutation m ON m.id = o.mutation_id
                    WHERE o.recorded_at >= :from AND o.recorded_at < :to %1$s) AS "objectionsRaised",
                  (SELECT count(*) FROM sec.audit_log a
                    WHERE a.occurred_at >= :from AND a.occurred_at < :to %2$s
                      AND a.action = 'OBJECTION_DISPOSED') AS "objectionsDisposed",
                  (SELECT count(*) FROM sec.audit_log a
                    WHERE a.occurred_at >= :from AND a.occurred_at < :to %2$s
                      AND a.action = 'OBJECTION_DISPOSED' AND a.decision = 'APPROVED') AS "objectionsUpheld",
                  (SELECT count(*) FROM sec.audit_log a
                    WHERE a.occurred_at >= :from AND a.occurred_at < :to %2$s
                      AND a.action = 'OBJECTION_DISPOSED' AND a.decision = 'REJECTED') AS "objectionsDismissed",
                  (SELECT count(*) FROM revenue.objection o
                     JOIN revenue.proposed_mutation m ON m.id = o.mutation_id
                    WHERE o.disposal_decision IS NULL %1$s) AS "objectionsOpen"
                """.formatted(state(f, "m"), state(f, "a"));
        return jdbc.queryForMap(sql, params(f));
    }

    public List<Map<String, Object>> byOffice(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT t.state_code AS "stateCode", t.sro_code AS "sroCode",
                       coalesce((SELECT max(s.sro_name)
                                   FROM master.sub_registrar_office s
                                   JOIN master.registration_district d ON d.id = s.district_id
                                  WHERE s.sro_code = t.sro_code AND d.state_code = t.state_code),
                                t.sro_code) AS "sroName",
                       count(*) AS "transactions",
                       count(*) FILTER (WHERE t.registered_at IS NOT NULL) AS "registered",
                       count(*) FILTER (WHERE t.status = 'EXCEPTION') AS "exceptions"
                  FROM core.transaction t
                 WHERE t.initiated_at >= :from AND t.initiated_at < :to %s
                 GROUP BY t.state_code, t.sro_code
                 ORDER BY count(*) DESC, t.sro_code
                 LIMIT 10
                """.formatted(state(f, "t")), params(f));
    }

    public List<Map<String, Object>> byState(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT s.state_code AS "stateCode", s.state_name AS "stateName",
                       (SELECT count(*) FROM core.transaction t
                         WHERE t.state_code = s.state_code
                           AND t.initiated_at >= :from AND t.initiated_at < :to) AS "transactions",
                       (SELECT count(*) FROM core.transaction t
                         WHERE t.state_code = s.state_code
                           AND t.registered_at >= :from AND t.registered_at < :to) AS "registered",
                       (SELECT count(*) FROM core.property p
                         WHERE p.state_code = s.state_code
                           AND p.created_at >= :from AND p.created_at < :to) AS "properties",
                       (SELECT count(*) FROM sec.audit_log a
                         WHERE a.state_code = s.state_code
                           AND a.occurred_at >= :from AND a.occurred_at < :to
                           AND a.to_status = 'EXCEPTION') AS "exceptions"
                  FROM cfg.state s
                 WHERE s.active %s
                 ORDER BY s.state_name
                """.formatted(state(f, "s")), params(f));
    }

    public List<Map<String, Object>> recentIssues(DashboardFilter f) {
        return jdbc.queryForList("""
                SELECT a.occurred_at AS "occurredAt", a.state_code AS "stateCode", a.action AS "action",
                       a.transaction_ref AS "transactionRef", a.property_ref AS "propertyRef",
                       a.actor_username AS "actor", a.outcome AS "outcome", a.to_status AS "toStatus",
                       a.detail AS "detail"
                  FROM sec.audit_log a
                 WHERE a.occurred_at >= :from AND a.occurred_at < :to %s
                   AND (a.to_status = 'EXCEPTION'
                        OR a.action = 'OBJECTION_RAISED'
                        OR (a.outcome = 'FAILURE' AND a.transaction_ref IS NOT NULL))
                 ORDER BY a.occurred_at DESC, a.id DESC
                 LIMIT 10
                """.formatted(state(f, "a")), params(f));
    }

    public Map<String, Object> records(DashboardFilter f, DashboardRecordQuery query) {
        MapSqlParameterSource params = params(f).addValue("limit", DashboardRecordQuery.LIMIT);
        if (query.search() != null) {
            params.addValue("search", query.likePattern());
        }
        if (query.status() != null) {
            params.addValue("status", query.status());
        }
        String[] parts = switch (query.dataset()) {
            case TRANSACTIONS -> transactionRecords(f, query);
            case PROPERTIES -> propertyRecords(f, query);
            case RULE_CHECKS -> ruleCheckRecords(f, query);
            case ISSUES -> issueRecords(f, query);
        };
        String base = parts[1];
        Long total = jdbc.queryForObject("SELECT count(*) " + base, params, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + parts[0] + " " + base + " ORDER BY " + parts[2] + " LIMIT :limit", params);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("rows", rows);
        return out;
    }

    private static String[] transactionRecords(DashboardFilter f, DashboardRecordQuery q) {
        String columns = """
                t.txn_ref AS "txnRef", t.state_code AS "stateCode", t.sro_code AS "sroCode",
                coalesce(o.sro_name, t.sro_code) AS "sroName", p.property_ref AS "propertyRef",
                t.deed_type_code AS "deedTypeCode", coalesce(d.name, t.deed_type_code) AS "deedType",
                t.status AS "status", t.initiated_at AS "initiatedAt", t.registered_at AS "registeredAt",
                (SELECT count(*) FROM rules.rule_check_request rq JOIN rules.rule_check_result rr ON rr.request_id = rq.id
                  WHERE rq.transaction_id = t.id) AS "ruleChecks",
                (SELECT count(*) FROM rules.rule_check_request rq JOIN rules.rule_check_result rr ON rr.request_id = rq.id
                  WHERE rq.transaction_id = t.id
                    AND rr.overall_outcome IN ('DISCREPANCY_DETECTED','REVIEW_REQUIRED','NOT_CHECKED')) AS "ruleFlags",
                (SELECT coalesce(sum(pay.amount), 0) FROM core.payment pay
                  WHERE pay.transaction_id = t.id AND pay.status = 'SUCCESS') AS "feesPaid"
                """;
        String base = """
                FROM core.transaction t
                JOIN core.property p ON p.id = t.property_id
                LEFT JOIN LATERAL (SELECT max(s.sro_name) AS sro_name
                                     FROM master.sub_registrar_office s
                                     JOIN master.registration_district rd ON rd.id = s.district_id
                                    WHERE s.sro_code = t.sro_code AND rd.state_code = t.state_code) o ON TRUE
                LEFT JOIN LATERAL (SELECT max(dt.name) AS name FROM master.deed_type dt
                                    WHERE dt.state_code = t.state_code AND dt.code = t.deed_type_code) d ON TRUE
               WHERE t.initiated_at >= :from AND t.initiated_at < :to %s %s %s
                """.formatted(state(f, "t"),
                q.status() == null ? "" : "AND t.status = :status",
                q.search() == null ? "" : """
                        AND (t.txn_ref ILIKE :search OR p.property_ref ILIKE :search OR t.sro_code ILIKE :search
                             OR o.sro_name ILIKE :search OR d.name ILIKE :search OR t.deed_type_code ILIKE :search)""");
        return new String[] { columns, base, "t.initiated_at DESC, t.id DESC" };
    }

    private static String[] propertyRecords(DashboardFilter f, DashboardRecordQuery q) {
        String columns = """
                p.property_ref AS "propertyRef", p.state_code AS "stateCode", p.district_code AS "districtCode",
                p.village_code AS "villageCode", p.sro_code AS "sroCode", p.survey_no AS "surveyNo",
                p.subdivision_no AS "subdivisionNo", p.property_type_code AS "propertyType",
                p.extent_value AS "extentValue", p.extent_unit AS "extentUnit", p.status AS "status",
                p.created_at AS "createdAt",
                (SELECT count(*) FROM core.transaction t WHERE t.property_id = p.id) AS "transactions"
                """;
        String base = """
                FROM core.property p
               WHERE p.created_at >= :from AND p.created_at < :to %s %s %s
                """.formatted(state(f, "p"),
                q.status() == null ? "" : "AND p.status = :status",
                q.search() == null ? "" : """
                        AND (p.property_ref ILIKE :search OR p.survey_no ILIKE :search OR p.ulpin ILIKE :search
                             OR p.sro_code ILIKE :search OR p.district_code ILIKE :search OR p.village_code ILIKE :search)""");
        return new String[] { columns, base, "p.created_at DESC, p.id DESC" };
    }

    private static String[] ruleCheckRecords(DashboardFilter f, DashboardRecordQuery q) {
        String columns = """
                r.checked_at AS "checkedAt", q.state_code AS "stateCode", t.txn_ref AS "txnRef",
                t.sro_code AS "sroCode", r.engine AS "engine", r.overall_outcome AS "outcome",
                r.reason_code AS "reasonCode", r.advisory AS "advisory", t.status AS "transactionStatus"
                """;
        String base = """
                FROM rules.rule_check_result r
                JOIN rules.rule_check_request q ON q.id = r.request_id
                JOIN core.transaction t ON t.id = q.transaction_id
               WHERE r.checked_at >= :from AND r.checked_at < :to %s %s %s
                """.formatted(state(f, "q"),
                q.status() == null ? "" : "AND r.overall_outcome = :status",
                q.search() == null ? "" : """
                        AND (t.txn_ref ILIKE :search OR r.reason_code ILIKE :search OR r.engine ILIKE :search
                             OR t.sro_code ILIKE :search)""");
        return new String[] { columns, base, "r.checked_at DESC, r.id DESC" };
    }

    private static String[] issueRecords(DashboardFilter f, DashboardRecordQuery q) {
        String columns = """
                a.occurred_at AS "occurredAt", a.state_code AS "stateCode",
                CASE WHEN a.to_status = 'EXCEPTION' THEN 'EXCEPTION'
                     WHEN a.action = 'OBJECTION_RAISED' THEN 'OBJECTION'
                     ELSE 'FAILURE' END AS "kind",
                a.action AS "action", a.transaction_ref AS "transactionRef", a.property_ref AS "propertyRef",
                a.actor_username AS "actor", a.actor_role AS "actorRole", a.detail AS "detail"
                """;
        String kind = q.status() == null ? "" : switch (q.status()) {
            case "EXCEPTION" -> "AND a.to_status = 'EXCEPTION'";
            case "OBJECTION" -> "AND a.action = 'OBJECTION_RAISED'";
            default -> "AND a.outcome = 'FAILURE' AND a.transaction_ref IS NOT NULL AND a.to_status IS DISTINCT FROM 'EXCEPTION'";
        };
        String base = """
                FROM sec.audit_log a
               WHERE a.occurred_at >= :from AND a.occurred_at < :to %s
                 AND (a.to_status = 'EXCEPTION'
                      OR a.action = 'OBJECTION_RAISED'
                      OR (a.outcome = 'FAILURE' AND a.transaction_ref IS NOT NULL)) %s %s
                """.formatted(state(f, "a"), kind,
                q.search() == null ? "" : """
                        AND (a.action ILIKE :search OR a.transaction_ref ILIKE :search OR a.property_ref ILIKE :search
                             OR a.actor_username ILIKE :search OR a.detail ILIKE :search)""");
        return new String[] { columns, base, "a.occurred_at DESC, a.id DESC" };
    }

    private static String state(DashboardFilter f, String alias) {
        return f.allStates() ? "" : "AND " + alias + ".state_code = :stateCode";
    }

    private static MapSqlParameterSource params(DashboardFilter f) {
        var params = new MapSqlParameterSource()
                .addValue("from", f.from())
                .addValue("to", f.to());
        if (!f.allStates()) {
            params.addValue("stateCode", f.stateCode());
        }
        return params;
    }
}
