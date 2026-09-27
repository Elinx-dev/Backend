package in.gov.slate.common;

import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes the append-only audit row for a state-changing action. Every mutating
 * endpoint calls this before it returns.
 */
@Service
public class AuditService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String action, String entityType, String entityId, String transactionRef,
                       String propertyRef, Map<String, ?> after, String detail) {
        record(action, entityType, entityId, transactionRef, propertyRef, null, after, "SUCCESS", detail);
    }

    public void record(String action, String entityType, String entityId, String transactionRef,
                       String propertyRef, Map<String, ?> before, Map<String, ?> after,
                       String outcome, String detail) {
        recordAs(null, action, entityType, entityId, transactionRef, propertyRef, before, after, outcome, detail);
    }

    /**
     * Audits an action that happens before a session exists, such as login: the
     * state code then comes from the account being acted on.
     */
    public void recordAs(String stateCode, String action, String entityType, String entityId,
                         String transactionRef, String propertyRef, Map<String, ?> before, Map<String, ?> after,
                         String outcome, String detail) {
        record(AuditEvent.of(action)
                .stateCode(stateCode)
                .entity(entityType, entityId)
                .transactionRef(transactionRef)
                .propertyRef(propertyRef)
                .before(before)
                .after(after)
                .outcome(outcome)
                .detail(detail)
                .build());
    }

    public void record(AuditEvent.Builder event) {
        record(event.build());
    }

    public void record(AuditEvent event) {
        CurrentUser user = CurrentUser.orNull();
        String effectiveStateCode = event.stateCode() != null ? event.stateCode()
                : (user != null ? user.stateCode() : RequestContext.stateCode());
        var params = new MapSqlParameterSource()
                .addValue("stateCode", effectiveStateCode)
                .addValue("actorUserId", user != null ? user.id() : null)
                .addValue("actorUsername", user != null ? user.username() : "SYSTEM")
                .addValue("actorRole", user != null ? String.join(",", user.roles()) : "SYSTEM")
                .addValue("action", event.action())
                .addValue("category", event.effectiveCategory())
                .addValue("entityType", event.entityType())
                .addValue("entityId", event.entityId())
                .addValue("transactionRef", event.transactionRef())
                .addValue("propertyRef", event.propertyRef())
                .addValue("fromStatus", event.fromStatus())
                .addValue("toStatus", event.toStatus())
                .addValue("stageCode", event.stageCode())
                .addValue("decision", event.effectiveDecision())
                .addValue("beforeJson", toJson(event.before()))
                .addValue("afterJson", toJson(event.after()))
                .addValue("requestId", RequestContext.requestId())
                .addValue("idempotencyKey", RequestContext.idempotencyKey())
                .addValue("httpMethod", RequestContext.httpMethod())
                .addValue("requestPath", RequestContext.requestPath())
                .addValue("ipAddress", RequestContext.ipAddress())
                .addValue("userAgent", truncate(RequestContext.userAgent(), 400))
                .addValue("outcome", event.outcome())
                .addValue("detail", truncate(event.detail(), 2000));
        jdbc.update("""
                INSERT INTO sec.audit_log (state_code, actor_user_id, actor_username, actor_role, action, category,
                    entity_type, entity_id, transaction_ref, property_ref, from_status, to_status, stage_code,
                    decision, before_json, after_json, request_id, idempotency_key, http_method, request_path,
                    ip_address, user_agent, outcome, detail)
                VALUES (:stateCode, :actorUserId, :actorUsername, :actorRole, :action, :category,
                    :entityType, :entityId, :transactionRef, :propertyRef, :fromStatus, :toStatus, :stageCode,
                    :decision, CAST(:beforeJson AS jsonb), CAST(:afterJson AS jsonb), :requestId, :idempotencyKey,
                    :httpMethod, :requestPath, :ipAddress, :userAgent, :outcome, :detail)
                """, params);
        RequestContext.markAudited();
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String toJson(Map<String, ?> value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
