package in.gov.slate.audit;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;

/**
 * A validated audit trail query. Every value the caller sends is checked and
 * normalised here, so the repository only ever binds known-good parameters and
 * an unknown sort column can never reach the SQL text.
 */
public record AuditFilter(OffsetDateTime from, OffsetDateTime to, Long actorUserId, String actorUsername,
                          String action, String category, String entityType, String entityId,
                          String transactionRef, String propertyRef, String outcome, String decision,
                          String search, String sort, boolean descending, int page, int size,
                          Long visibleUserId, List<String> visibleSroCodes, List<String> visibleVillageCodes,
                          boolean stateWide) {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_RANGE_DAYS = 400;

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9_/-]{1,64}");
    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE");
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");
    private static final Set<String> CATEGORIES = Set.of("TRANSACTION", "PROPERTY", "APPROVAL", "CONFIGURATION");
    private static final List<String> SORTS = List.of("occurred_at", "action", "actor_username", "entity_type",
            "category", "outcome");

    public static Builder builder() {
        return new Builder();
    }

    public int offset() {
        return page * size;
    }

    /** Collects raw request parameters and validates them on {@link Builder#build()}. */
    public static final class Builder {
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
        private CurrentUser visibilityUser;

        public Builder from(String value) {
            this.from = value;
            return this;
        }

        public Builder to(String value) {
            this.to = value;
            return this;
        }

        public Builder actorUserId(Long value) {
            this.actorUserId = value;
            return this;
        }

        public Builder actorUsername(String value) {
            this.actorUsername = value;
            return this;
        }

        public Builder action(String value) {
            this.action = value;
            return this;
        }

        public Builder category(String value) {
            this.category = value;
            return this;
        }

        public Builder entityType(String value) {
            this.entityType = value;
            return this;
        }

        public Builder entityId(String value) {
            this.entityId = value;
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

        public Builder outcome(String value) {
            this.outcome = value;
            return this;
        }

        public Builder decision(String value) {
            this.decision = value;
            return this;
        }

        public Builder search(String value) {
            this.search = value;
            return this;
        }

        public Builder sort(String value) {
            this.sort = value;
            return this;
        }

        public Builder direction(String value) {
            this.direction = value;
            return this;
        }

        public Builder page(Integer value) {
            this.page = value;
            return this;
        }

        public Builder size(Integer value) {
            this.size = value;
            return this;
        }

        public Builder scope(CurrentUser user) {
            this.visibilityUser = user;
            return this;
        }

        public AuditFilter build() {
            OffsetDateTime parsedFrom = timestamp("from", from, false);
            OffsetDateTime parsedTo = timestamp("to", to, true);
            if (parsedFrom != null && parsedTo != null) {
                if (parsedTo.isBefore(parsedFrom)) {
                    throw ApiException.badRequest("from must not be after to");
                }
                if (Duration.between(parsedFrom, parsedTo).toDays() > MAX_RANGE_DAYS) {
                    throw ApiException.badRequest("The date range may not exceed " + MAX_RANGE_DAYS + " days");
                }
            }
            if (actorUserId != null && actorUserId <= 0) {
                throw ApiException.badRequest("actorUserId must be a positive identifier");
            }
            int resolvedPage = page == null ? 0 : page;
            if (resolvedPage < 0) {
                throw ApiException.badRequest("page must not be negative");
            }
            int resolvedSize = size == null ? 50 : size;
            if (resolvedSize < 1 || resolvedSize > MAX_PAGE_SIZE) {
                throw ApiException.badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
            }
            String resolvedSort = blankToNull(sort) == null ? "occurred_at" : sort.trim().toLowerCase();
            if (!SORTS.contains(resolvedSort)) {
                throw ApiException.badRequest("sort must be one of " + String.join(", ", SORTS));
            }
            String resolvedDirection = blankToNull(direction) == null ? "desc" : direction.trim().toLowerCase();
            if (!resolvedDirection.equals("asc") && !resolvedDirection.equals("desc")) {
                throw ApiException.badRequest("direction must be asc or desc");
            }
            return new AuditFilter(parsedFrom, parsedTo, actorUserId,
                    pattern("actorUsername", actorUsername, CODE, false),
                    upper(pattern("action", action, CODE, true)),
                    member("category", upper(blankToNull(category)), CATEGORIES),
                    upper(pattern("entityType", entityType, CODE, true)),
                    pattern("entityId", entityId, REF, false),
                    upper(pattern("transactionRef", transactionRef, REF, true)),
                    upper(pattern("propertyRef", propertyRef, REF, true)),
                    member("outcome", upper(blankToNull(outcome)), OUTCOMES),
                    member("decision", upper(blankToNull(decision)), DECISIONS),
                    text("search", search, 120),
                    resolvedSort, resolvedDirection.equals("desc"), resolvedPage, resolvedSize,
                    visibilityUser == null ? null : visibilityUser.id(),
                    visibilityUser == null ? List.of() : List.copyOf(visibilityUser.sroCodes()),
                    visibilityUser == null ? List.of() : List.copyOf(visibilityUser.villageCodes()),
                    visibilityUser != null && visibilityUser.hasRole("STATE_ADMIN"));
        }

        private OffsetDateTime timestamp(String field, String value, boolean endOfDay) {
            String trimmed = blankToNull(value);
            if (trimmed == null) {
                return null;
            }
            try {
                if (trimmed.length() == 10) {
                    LocalDate date = LocalDate.parse(trimmed);
                    return (endOfDay ? date.plusDays(1).atStartOfDay() : date.atStartOfDay())
                            .atOffset(ZoneOffset.UTC);
                }
                return OffsetDateTime.parse(trimmed);
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest(field + " must be an ISO-8601 date or timestamp");
            }
        }

        private String pattern(String field, String value, Pattern allowed, boolean uppercased) {
            String trimmed = blankToNull(value);
            if (trimmed == null) {
                return null;
            }
            if (!allowed.matcher(trimmed).matches()) {
                throw ApiException.badRequest(field + " contains unsupported characters");
            }
            return uppercased ? trimmed.toUpperCase() : trimmed;
        }

        private String member(String field, String value, Set<String> allowed) {
            if (value != null && !allowed.contains(value)) {
                throw ApiException.badRequest(field + " must be one of " + String.join(", ", allowed));
            }
            return value;
        }

        private String text(String field, String value, int max) {
            String trimmed = blankToNull(value);
            if (trimmed == null) {
                return null;
            }
            if (trimmed.length() > max) {
                throw ApiException.badRequest(field + " may not exceed " + max + " characters");
            }
            return trimmed;
        }

        private String upper(String value) {
            return value == null ? null : value.toUpperCase();
        }

        private String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
