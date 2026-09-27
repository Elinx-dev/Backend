package in.gov.slate.common;

import java.util.Map;

/**
 * One audited user action. Everything the audit trail screen filters on -
 * property, transaction, approval decision and workflow status change - is a
 * first-class field so it can be indexed rather than parsed out of the payload.
 */
public record AuditEvent(String stateCode, String category, String action, String entityType, String entityId,
                         String transactionRef, String propertyRef, String fromStatus, String toStatus,
                         String stageCode, String decision, Map<String, ?> before, Map<String, ?> after,
                         String outcome, String detail) {

    public static final String CATEGORY_TRANSACTION = "TRANSACTION";
    public static final String CATEGORY_PROPERTY = "PROPERTY";
    public static final String CATEGORY_APPROVAL = "APPROVAL";
    public static final String CATEGORY_SECURITY = "SECURITY";
    public static final String CATEGORY_CONFIGURATION = "CONFIGURATION";
    public static final String CATEGORY_NAVIGATION = "NAVIGATION";
    public static final String CATEGORY_SYSTEM = "SYSTEM";

    public static final String DECISION_APPROVED = "APPROVED";
    public static final String DECISION_REJECTED = "REJECTED";

    public static Builder of(String action) {
        return new Builder(action);
    }

    /** The category to store when the caller did not set one explicitly. */
    public String effectiveCategory() {
        if (category != null) {
            return category;
        }
        if (decision != null) {
            return CATEGORY_APPROVAL;
        }
        if ("USER".equals(entityType) || "SESSION".equals(entityType)) {
            return CATEGORY_SECURITY;
        }
        if (transactionRef != null) {
            return CATEGORY_TRANSACTION;
        }
        if (propertyRef != null) {
            return CATEGORY_PROPERTY;
        }
        return CATEGORY_SYSTEM;
    }

    /** APPROVED / REJECTED inferred from the action code when not set explicitly. */
    public String effectiveDecision() {
        if (decision != null) {
            return decision;
        }
        if (action == null) {
            return null;
        }
        String upper = action.toUpperCase();
        if (upper.contains("REJECT")) {
            return DECISION_REJECTED;
        }
        if (upper.contains("APPROVE")) {
            return DECISION_APPROVED;
        }
        return null;
    }

    public static final class Builder {
        private final String action;
        private String stateCode;
        private String category;
        private String entityType = "SYSTEM";
        private String entityId;
        private String transactionRef;
        private String propertyRef;
        private String fromStatus;
        private String toStatus;
        private String stageCode;
        private String decision;
        private Map<String, ?> before;
        private Map<String, ?> after;
        private String outcome = "SUCCESS";
        private String detail;

        private Builder(String action) {
            this.action = action;
        }

        public Builder stateCode(String value) {
            this.stateCode = value;
            return this;
        }

        public Builder category(String value) {
            this.category = value;
            return this;
        }

        public Builder entity(String type, String id) {
            this.entityType = type;
            this.entityId = id;
            return this;
        }

        public Builder transactionRef(String value) {
            this.transactionRef = value;
            return this;
        }

        public Builder propertyRef(String value) {
            this.propertyRef = value;
            return this;
        }

        public Builder statusChange(String from, String to) {
            this.fromStatus = from;
            this.toStatus = to;
            return this;
        }

        public Builder stageCode(String value) {
            this.stageCode = value;
            return this;
        }

        public Builder decision(String value) {
            this.decision = value;
            return this;
        }

        public Builder before(Map<String, ?> value) {
            this.before = value;
            return this;
        }

        public Builder after(Map<String, ?> value) {
            this.after = value;
            return this;
        }

        public Builder outcome(String value) {
            this.outcome = value;
            return this;
        }

        public Builder detail(String value) {
            this.detail = value;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(stateCode, category, action, entityType, entityId, transactionRef, propertyRef,
                    fromStatus, toStatus, stageCode, decision, before, after, outcome, detail);
        }
    }
}
