package in.gov.slate.audit;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditEvent;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;

/**
 * Serves the audit trail. A state administrator sees every action taken in the
 * state; anyone else sees only their own, and only ever within their own state.
 */
@Service
public class AuditQueryService {

    private static final String SUPERVISOR_ROLE = "STATE_ADMIN";

    private final AuditRepository repository;
    private final AuditService audit;

    public AuditQueryService(AuditRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public Map<String, Object> search(AuditFilter.Builder request) {
        CurrentUser user = CurrentUser.require();
        AuditFilter filter = scoped(request, user);
        List<Map<String, Object>> rows = repository.search(filter, user.stateCode());
        long total = repository.count(filter, user.stateCode());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", rows);
        response.put("page", filter.page());
        response.put("size", filter.size());
        response.put("total", total);
        response.put("totalPages", (int) Math.ceil((double) total / filter.size()));
        response.put("scope", scopeOf(user));
        return response;
    }

    public Map<String, Object> summary(AuditFilter.Builder request) {
        CurrentUser user = CurrentUser.require();
        return repository.summary(scoped(request, user), user.stateCode());
    }

    public Map<String, Object> facets() {
        CurrentUser user = CurrentUser.require();
        Map<String, Object> facets = new LinkedHashMap<>(repository.facets(user.stateCode()));
        facets.put("scope", scopeOf(user));
        return facets;
    }

    public Map<String, Object> entry(long id) {
        CurrentUser user = CurrentUser.require();
        Map<String, Object> row = repository.findById(id, user.stateCode())
                .orElseThrow(() -> ApiException.notFound("Audit entry " + id));
        if (!user.hasRole(SUPERVISOR_ROLE) && !Long.valueOf(user.id()).equals(actorId(row))) {
            throw ApiException.forbidden("You may only read your own audit entries");
        }
        return row;
    }

    /** Timeline for one transaction or property, used by the detail screens. */
    public List<Map<String, Object>> timeline(String transactionRef, String propertyRef, Integer size) {
        CurrentUser user = CurrentUser.require();
        if (transactionRef == null && propertyRef == null) {
            throw ApiException.badRequest("A transactionRef or propertyRef is required for a timeline");
        }
        AuditFilter filter = AuditFilter.builder()
                .transactionRef(transactionRef)
                .propertyRef(propertyRef)
                .size(size == null ? 100 : size)
                .build();
        return repository.search(filter, user.stateCode());
    }

    public String csv(AuditFilter.Builder request) {
        CurrentUser user = CurrentUser.require();
        AuditFilter filter = scoped(request.size(AuditFilter.MAX_PAGE_SIZE).page(0), user);
        List<String> columns = List.of("id", "occurred_at", "actor_username", "actor_role", "action", "category",
                "entity_type", "entity_id", "transaction_ref", "property_ref", "from_status", "to_status",
                "decision", "outcome", "ip_address", "detail");
        StringBuilder csv = new StringBuilder(String.join(",", columns)).append('\n');
        for (Map<String, Object> row : repository.search(filter, user.stateCode())) {
            csv.append(columns.stream().map(c -> quote(row.get(c))).reduce((a, b) -> a + "," + b).orElse(""))
                    .append('\n');
        }
        audit.record(AuditEvent.of("AUDIT_TRAIL_EXPORTED")
                .category(AuditEvent.CATEGORY_SECURITY)
                .entity("AUDIT_LOG", null)
                .detail("Exported the audit trail as CSV"));
        return csv.toString();
    }

    /**
     * Records a user action that never reaches another endpoint, such as opening
     * a screen, so the trail covers navigation as well as data changes.
     */
    public void recordUiEvent(String action, String page, String entityType, String entityId,
                              String transactionRef, String propertyRef, String detail) {
        audit.record(AuditEvent.of(action)
                .category(AuditEvent.CATEGORY_NAVIGATION)
                .entity(entityType == null ? "SCREEN" : entityType, entityId == null ? page : entityId)
                .transactionRef(transactionRef)
                .propertyRef(propertyRef)
                .detail(detail == null ? page : detail));
    }

    private AuditFilter scoped(AuditFilter.Builder request, CurrentUser user) {
        if (!user.hasRole(SUPERVISOR_ROLE)) {
            request.actorUserId(user.id()).actorUsername(user.username());
        }
        return request.build();
    }

    private String scopeOf(CurrentUser user) {
        return user.hasRole(SUPERVISOR_ROLE) ? "STATE" : "SELF";
    }

    private Long actorId(Map<String, Object> row) {
        Object value = row.get("actor_user_id");
        return value instanceof Number number ? number.longValue() : null;
    }

    private String quote(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof OffsetDateTime timestamp ? timestamp.toString() : String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
