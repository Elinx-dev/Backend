package in.gov.slate.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads sec.audit_log. The table is append-only, so this class never writes. */
@Repository
public class AuditRepository {

    /**
     * Rows written before the audit trail carried a category still have to be
     * classified, so the same rules the writer applies are mirrored in SQL.
     */
    static final String CATEGORY = """
            COALESCE(a.category,
                     CASE WHEN a.action ILIKE '%APPROVE%' OR a.action ILIKE '%REJECT%' THEN 'APPROVAL'
                          WHEN a.entity_type IN ('USER','SESSION') THEN 'SECURITY'
                          WHEN a.transaction_ref IS NOT NULL THEN 'TRANSACTION'
                          WHEN a.property_ref IS NOT NULL THEN 'PROPERTY'
                          ELSE 'SYSTEM' END)""";

    static final String DECISION = """
            COALESCE(a.decision,
                     CASE WHEN a.action ILIKE '%REJECT%' THEN 'REJECTED'
                          WHEN a.action ILIKE '%APPROVE%' THEN 'APPROVED' END)""";

    private static final String COLUMNS = "a.id, a.occurred_at, a.state_code, a.actor_user_id, a.actor_username,"
            + " a.actor_role, a.action, " + CATEGORY + " AS category, a.entity_type, a.entity_id,"
            + " a.transaction_ref, a.property_ref, a.from_status, a.to_status, a.stage_code, "
            + DECISION + " AS decision, a.before_json, a.after_json, a.request_id, a.idempotency_key,"
            + " a.http_method, a.request_path, a.ip_address, a.outcome, a.detail";

    private final NamedParameterJdbcTemplate jdbc;

    public AuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(AuditFilter filter, String stateCode) {
        Where where = where(filter, stateCode);
        String sql = "SELECT " + COLUMNS + " FROM sec.audit_log a WHERE " + where.clause()
                + " ORDER BY a." + filter.sort() + (filter.descending() ? " DESC" : " ASC")
                + ", a.id " + (filter.descending() ? "DESC" : "ASC")
                + " LIMIT :limit OFFSET :offset";
        return jdbc.queryForList(sql, where.params()
                .addValue("limit", filter.size())
                .addValue("offset", filter.offset()));
    }

    public long count(AuditFilter filter, String stateCode) {
        Where where = where(filter, stateCode);
        Long total = jdbc.queryForObject("SELECT count(*) FROM sec.audit_log a WHERE " + where.clause(),
                where.params(), Long.class);
        return total == null ? 0L : total;
    }

    /** Counts per category, outcome and approval decision for the filtered set. */
    public Map<String, Object> summary(AuditFilter filter, String stateCode) {
        Where where = where(filter, stateCode);
        return Map.of(
                "byCategory", jdbc.queryForList("SELECT " + CATEGORY + " AS category, count(*) AS events"
                        + " FROM sec.audit_log a WHERE " + where.clause()
                        + " GROUP BY 1 ORDER BY 2 DESC", where.params()),
                "byOutcome", jdbc.queryForList("SELECT a.outcome, count(*) AS events"
                        + " FROM sec.audit_log a WHERE " + where.clause()
                        + " GROUP BY 1 ORDER BY 2 DESC", where.params()),
                "byDecision", jdbc.queryForList("SELECT " + DECISION + " AS decision, count(*) AS events"
                        + " FROM sec.audit_log a WHERE " + where.clause()
                        + " AND " + DECISION + " IS NOT NULL GROUP BY 1 ORDER BY 2 DESC", where.params()),
                "byActor", jdbc.queryForList("SELECT a.actor_username, a.actor_role, count(*) AS events,"
                        + " max(a.occurred_at) AS last_action_at"
                        + " FROM sec.audit_log a WHERE " + where.clause()
                        + " GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 20", where.params()));
    }

    public Optional<Map<String, Object>> findById(long id, String stateCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + COLUMNS
                + " FROM sec.audit_log a WHERE a.id = :id AND a.state_code = :stateCode",
                new MapSqlParameterSource().addValue("id", id).addValue("stateCode", stateCode));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Distinct values for the audit trail filter controls. */
    public Map<String, Object> facets(String stateCode) {
        var params = new MapSqlParameterSource("stateCode", stateCode);
        return Map.of(
                "actions", jdbc.queryForList("SELECT DISTINCT action FROM sec.audit_log"
                        + " WHERE state_code = :stateCode ORDER BY 1", params, String.class),
                "entityTypes", jdbc.queryForList("SELECT DISTINCT entity_type FROM sec.audit_log"
                        + " WHERE state_code = :stateCode ORDER BY 1", params, String.class),
                "categories", jdbc.queryForList("SELECT DISTINCT " + CATEGORY + " FROM sec.audit_log a"
                        + " WHERE a.state_code = :stateCode ORDER BY 1", params, String.class),
                "actors", jdbc.queryForList("""
                        SELECT actor_user_id, actor_username, max(actor_role) AS actor_role
                          FROM sec.audit_log
                         WHERE state_code = :stateCode AND actor_username IS NOT NULL
                         GROUP BY 1, 2 ORDER BY 2
                        """, params),
                "outcomes", List.of("SUCCESS", "FAILURE"),
                "decisions", List.of("APPROVED", "REJECTED"));
    }

    private record Where(String clause, MapSqlParameterSource params) {
    }

    private Where where(AuditFilter filter, String stateCode) {
        List<String> clauses = new ArrayList<>();
        var params = new MapSqlParameterSource();
        clauses.add("a.state_code = :stateCode");
        params.addValue("stateCode", stateCode);
        add(clauses, params, filter.from(), "a.occurred_at >= :from", "from");
        add(clauses, params, filter.to(), "a.occurred_at < :to", "to");
        add(clauses, params, filter.actorUserId(), "a.actor_user_id = :actorUserId", "actorUserId");
        add(clauses, params, filter.actorUsername(), "a.actor_username = :actorUsername", "actorUsername");
        add(clauses, params, filter.action(), "a.action = :action", "action");
        add(clauses, params, filter.entityType(), "a.entity_type = :entityType", "entityType");
        add(clauses, params, filter.entityId(), "a.entity_id = :entityId", "entityId");
        add(clauses, params, filter.transactionRef(), "a.transaction_ref = :transactionRef", "transactionRef");
        add(clauses, params, filter.propertyRef(), "a.property_ref = :propertyRef", "propertyRef");
        add(clauses, params, filter.outcome(), "a.outcome = :outcome", "outcome");
        add(clauses, params, filter.category(), CATEGORY + " = :category", "category");
        add(clauses, params, filter.decision(), DECISION + " = :decision", "decision");
        if (filter.search() != null) {
            clauses.add("""
                    (a.action ILIKE :search OR a.entity_id ILIKE :search OR a.actor_username ILIKE :search
                     OR a.transaction_ref ILIKE :search OR a.property_ref ILIKE :search OR a.detail ILIKE :search
                     OR a.request_path ILIKE :search)""");
            params.addValue("search", "%" + escapeLike(filter.search()) + "%");
        }
        return new Where(String.join(" AND ", clauses), params);
    }

    /** Keeps wildcards typed into the search box literal. */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void add(List<String> clauses, MapSqlParameterSource params, Object value, String clause, String name) {
        if (value != null) {
            clauses.add(clause);
            params.addValue(name, value);
        }
    }
}
