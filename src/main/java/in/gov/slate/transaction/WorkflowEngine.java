package in.gov.slate.transaction;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.config.ConfigService;

/**
 * Drives cfg.workflow_transition. A transition happens only when it is configured
 * for the current status, allowed for the caller's role, and its guard passes.
 * There is no REJECTED / RETURNED / CANCELLED transition anywhere in the model.
 */
@Service
public class WorkflowEngine {

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService config;
    private final ValidationEngine validation;
    private final AuditService audit;

    public WorkflowEngine(NamedParameterJdbcTemplate jdbc, ConfigService config,
                          ValidationEngine validation, AuditService audit) {
        this.jdbc = jdbc;
        this.config = config;
        this.validation = validation;
        this.audit = audit;
    }

    public List<Map<String, Object>> availableActions(TransactionContext ctx, CurrentUser user) {
        return transitionsFrom(ctx).stream()
                .filter(t -> roleAllowed(t, user))
                .filter(t -> guardPasses(t, ctx))
                .map(t -> Map.<String, Object>of(
                        "actionCode", t.get("action_code"),
                        "toStatus", t.get("to_status"),
                        "requiresReason", t.get("requires_reason")))
                .toList();
    }

    public String apply(TransactionContext ctx, String actionCode, String reason, CurrentUser user) {
        Map<String, Object> transition = transitionsFrom(ctx).stream()
                .filter(t -> actionCode.equals(t.get("action_code")))
                .findFirst()
                .orElseThrow(() -> ApiException.conflict(
                        "Action " + actionCode + " is not available from status " + ctx.status()));

        if (!roleAllowed(transition, user)) {
            throw ApiException.forbidden("Your role may not perform " + actionCode);
        }
        if (Boolean.TRUE.equals(transition.get("requires_reason")) && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("A reason is required for " + actionCode);
        }
        if (!guardPasses(transition, ctx)) {
            // Surface the specific validation failures rather than a bare guard name.
            validation.evaluate(ctx, "TRANSACTION", true);
            throw ApiException.conflict("Guard " + transition.get("guard_expr") + " is not satisfied");
        }

        String toStatus = (String) transition.get("to_status");
        String stage = stageForStatus(ctx, toStatus);
        var params = new MapSqlParameterSource()
                .addValue("id", ctx.id())
                .addValue("status", toStatus)
                .addValue("stage", stage)
                .addValue("reason", reason);
        jdbc.update("""
                UPDATE core.transaction
                   SET status = :status,
                       current_stage_code = :stage,
                       withdrawn_at = CASE WHEN :status = 'WITHDRAWN' THEN now() ELSE withdrawn_at END,
                       withdrawn_reason = CASE WHEN :status = 'WITHDRAWN' THEN :reason ELSE withdrawn_reason END
                 WHERE id = :id
                """, params);

        audit.record("TRANSITION_" + actionCode, "TRANSACTION", String.valueOf(ctx.id()), ctx.txnRef(),
                ctx.propertyRef(), Map.of("status", ctx.status()),
                Map.of("status", toStatus, "stage", stage), "SUCCESS", reason);
        return toStatus;
    }

    private List<Map<String, Object>> transitionsFrom(TransactionContext ctx) {
        return jdbc.queryForList("""
                SELECT action_code, from_status, to_status, allowed_roles, guard_expr, requires_reason
                  FROM cfg.workflow_transition
                 WHERE workflow_id = :workflowId AND from_status = :status
                 ORDER BY id
                """, new MapSqlParameterSource()
                .addValue("workflowId", ctx.transaction().get("workflow_id"))
                .addValue("status", ctx.status()));
    }

    private boolean roleAllowed(Map<String, Object> transition, CurrentUser user) {
        String[] roles = toArray(transition.get("allowed_roles"));
        for (String role : roles) {
            if ("SYSTEM".equals(role) || user.hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    private boolean guardPasses(Map<String, Object> transition, TransactionContext ctx) {
        String guard = (String) transition.get("guard_expr");
        return guard == null || validation.guard(guard, ctx);
    }

    private String stageForStatus(TransactionContext ctx, String status) {
        return Optional.ofNullable(jdbc.query("""
                SELECT stage_code FROM cfg.workflow_stage
                 WHERE workflow_id = :workflowId AND status_on_enter = :status
                 ORDER BY seq LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("workflowId", ctx.transaction().get("workflow_id"))
                .addValue("status", status), rs -> rs.next() ? rs.getString(1) : null))
                .orElse((String) ctx.transaction().get("current_stage_code"));
    }

    private String[] toArray(Object sqlArray) {
        if (sqlArray == null) {
            return new String[0];
        }
        try {
            if (sqlArray instanceof java.sql.Array array) {
                return (String[]) array.getArray();
            }
            return new String[]{sqlArray.toString()};
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable allowed_roles value", e);
        }
    }

    public ConfigService config() {
        return config;
    }
}
