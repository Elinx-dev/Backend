package in.gov.slate.audit;

/**
 * Raw query string of an audit trail request. Values stay untyped here and are
 * validated by {@link AuditFilter}, so a malformed filter fails the same way
 * whichever endpoint it was sent to.
 */
public class AuditQueryParams {

    private String from;
    private String to;
    private Long actorUserId;
    private String actorUsername;
    private String action;
    private String category;
    private String entityType;
    private String entityId;
    private String transactionRef;
    private String propertyRef;
    private String outcome;
    private String decision;
    private String search;
    private String sort;
    private String direction;
    private Integer page;
    private Integer size;

    public AuditFilter.Builder toFilter() {
        return AuditFilter.builder()
                .from(from)
                .to(to)
                .actorUserId(actorUserId)
                .actorUsername(actorUsername)
                .action(action)
                .category(category)
                .entityType(entityType)
                .entityId(entityId)
                .transactionRef(transactionRef)
                .propertyRef(propertyRef)
                .outcome(outcome)
                .decision(decision)
                .search(search)
                .sort(sort)
                .direction(direction)
                .page(page)
                .size(size);
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public void setTransactionRef(String transactionRef) {
        this.transactionRef = transactionRef;
    }

    public void setPropertyRef(String propertyRef) {
        this.propertyRef = propertyRef;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public void setSearch(String search) {
        this.search = search;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public void setSize(Integer size) {
        this.size = size;
    }
}
