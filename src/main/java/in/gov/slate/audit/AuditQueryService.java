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
import in.gov.slate.security.AdminStateScope;

/**
 * Serves application-change events. Administrators see their whole state;
 * other users see their own actions and changes to transactions assigned to them.
 */
@Service
public class AuditQueryService {

    private final AuditRepository repository;
    private final AuditService audit;
    private final AdminStateScope stateScope;

    public AuditQueryService(AuditRepository repository, AuditService audit, AdminStateScope stateScope) {
        this.repository = repository;
        this.audit = audit;
        this.stateScope = stateScope;
    }

    private record Target(String stateCode, boolean stateWide) {
    }

    public Map<String, Object> search(AuditFilter.Builder request, String requestedState) {
        CurrentUser user = CurrentUser.require();
        Target target = target(user, requestedState);
        AuditFilter filter = scoped(request, user, target);
        List<Map<String, Object>> rows = repository.search(filter, target.stateCode());
        long total = repository.count(filter, target.stateCode());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", rows);
        response.put("page", filter.page());
        response.put("size", filter.size());
        response.put("total", total);
        response.put("totalPages", (int) Math.ceil((double) total / filter.size()));
        response.put("scope", scopeOf(target));
        return response;
    }

    public Map<String, Object> summary(AuditFilter.Builder request, String requestedState) {
        CurrentUser user = CurrentUser.require();
        Target target = target(user, requestedState);
        return repository.summary(scoped(request, user, target), target.stateCode());
    }

    public Map<String, Object> facets(String requestedState) {
        CurrentUser user = CurrentUser.require();
        Target target = target(user, requestedState);
        Map<String, Object> facets = new LinkedHashMap<>(repository.facets(target.stateCode()));
        facets.put("scope", scopeOf(target));
        return facets;
    }

    public Map<String, Object> entry(long id, String requestedState) {
        CurrentUser user = CurrentUser.require();
        Target target = target(user, requestedState);
        Map<String, Object> row = repository.findVisibleById(id, scoped(AuditFilter.builder(), user, target),
            target.stateCode())
                .orElseThrow(() -> ApiException.notFound("Audit entry " + id));
        return row;
    }

    /**
     * Timeline for one transaction or property, used by the detail screens. Anyone who may open
     * the record (it is in their state) sees all of its business events, not only their own.
     */
    public List<Map<String, Object>> timeline(String transactionRef, String propertyRef, Integer size,
                                               String requestedState) {
        CurrentUser user = CurrentUser.require();
        if (transactionRef == null && propertyRef == null) {
            throw ApiException.badRequest("A transactionRef or propertyRef is required for a timeline");
        }
        Target target = target(user, requestedState);
        if (!repository.recordExists(transactionRef, propertyRef, target.stateCode())) {
            throw ApiException.notFound(transactionRef != null ? "Transaction " + transactionRef
                    : "Property " + propertyRef);
        }
        AuditFilter filter = AuditFilter.builder()
                .transactionRef(transactionRef)
                .propertyRef(propertyRef)
                .size(size == null ? 100 : size)
                .scope(user, true)
                .build();
        return repository.search(filter, target.stateCode());
    }

    public String csv(AuditFilter.Builder request, String requestedState) {
        CurrentUser user = CurrentUser.require();
        Target target = target(user, requestedState);
        AuditFilter filter = scoped(request.size(AuditFilter.MAX_PAGE_SIZE).page(0), user, target);
        List<String> columns = List.of("id", "occurred_at", "actor_username", "actor_role", "action", "category",
                "entity_type", "entity_id", "transaction_ref", "property_ref", "from_status", "to_status",
                "decision", "outcome", "ip_address", "detail");
        StringBuilder csv = new StringBuilder(String.join(",", columns)).append('\n');
        for (Map<String, Object> row : repository.search(filter, target.stateCode())) {
            csv.append(columns.stream().map(c -> quote(row.get(c))).reduce((a, b) -> a + "," + b).orElse(""))
                    .append('\n');
        }
        audit.record(AuditEvent.of("AUDIT_TRAIL_EXPORTED").stateCode(target.stateCode())
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

    private AuditFilter scoped(AuditFilter.Builder request, CurrentUser user, Target target) {
        return request.scope(user, target.stateWide()).build();
    }

    private Target target(CurrentUser user, String requestedState) {
        if (user.hasRole("CENTRAL_ADMIN") || user.hasRole("STATE_ADMIN")) {
            return new Target(stateScope.resolve(requestedState), true);
        }
        if (requestedState != null && !requestedState.isBlank()) {
            throw ApiException.forbidden("Only administrators can select an audit state");
        }
        return new Target(user.stateCode(), false);
    }

    private String scopeOf(Target target) {
        return target.stateWide() ? "STATE" : "RELATED";
    }

    private String quote(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof OffsetDateTime timestamp ? timestamp.toString() : String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
