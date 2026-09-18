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
        CurrentUser user = CurrentUser.orNull();
        String effectiveStateCode = stateCode != null ? stateCode
                : (user != null ? user.stateCode() : RequestContext.stateCode());
        var params = new MapSqlParameterSource()
                .addValue("stateCode", effectiveStateCode)
                .addValue("actorUserId", user != null ? user.id() : null)
                .addValue("actorUsername", user != null ? user.username() : "SYSTEM")
                .addValue("actorRole", user != null ? String.join(",", user.roles()) : "SYSTEM")
                .addValue("action", action)
                .addValue("entityType", entityType)
                .addValue("entityId", entityId)
                .addValue("transactionRef", transactionRef)
                .addValue("propertyRef", propertyRef)
                .addValue("beforeJson", toJson(before))
                .addValue("afterJson", toJson(after))
                .addValue("requestId", RequestContext.requestId())
                .addValue("idempotencyKey", RequestContext.idempotencyKey())
                .addValue("outcome", outcome)
                .addValue("detail", detail);
        jdbc.update("""
                INSERT INTO sec.audit_log (state_code, actor_user_id, actor_username, actor_role, action,
                    entity_type, entity_id, transaction_ref, property_ref, before_json, after_json,
                    request_id, idempotency_key, outcome, detail)
                VALUES (:stateCode, :actorUserId, :actorUsername, :actorRole, :action,
                    :entityType, :entityId, :transactionRef, :propertyRef,
                    CAST(:beforeJson AS jsonb), CAST(:afterJson AS jsonb),
                    :requestId, :idempotencyKey, :outcome, :detail)
                """, params);
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
