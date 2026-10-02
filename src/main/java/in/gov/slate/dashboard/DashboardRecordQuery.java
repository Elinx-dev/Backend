package in.gov.slate.dashboard;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import in.gov.slate.common.ApiException;

/** Validated search parameters for the dashboard data explorer. */
public record DashboardRecordQuery(Dataset dataset, String search, String status) {

    public static final int LIMIT = 200;
    static final int MAX_SEARCH_LENGTH = 100;
    static final Set<String> ISSUE_KINDS = Set.of("FAILURE", "EXCEPTION", "OBJECTION");
    private static final Pattern STATUS = Pattern.compile("[A-Z_]{1,40}");

    public enum Dataset { TRANSACTIONS, PROPERTIES, RULE_CHECKS, ISSUES }

    public static DashboardRecordQuery of(String dataset, String search, String status) {
        Dataset parsedDataset;
        try {
            parsedDataset = dataset == null || dataset.isBlank()
                    ? Dataset.TRANSACTIONS
                    : Dataset.valueOf(dataset.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown dataset " + dataset);
        }

        String term = search == null ? null : search.trim();
        if (term != null && term.isEmpty()) {
            term = null;
        }
        if (term != null && term.length() > MAX_SEARCH_LENGTH) {
            throw ApiException.badRequest("Search text cannot exceed " + MAX_SEARCH_LENGTH + " characters");
        }

        String code = status == null ? null : status.trim().toUpperCase(Locale.ROOT);
        if (code != null && (code.isEmpty() || "ALL".equals(code))) {
            code = null;
        }
        if (code != null && !STATUS.matcher(code).matches()) {
            throw ApiException.badRequest("Invalid status filter");
        }
        if (code != null && parsedDataset == Dataset.ISSUES && !ISSUE_KINDS.contains(code)) {
            throw ApiException.badRequest("Issue type must be one of FAILURE, EXCEPTION or OBJECTION");
        }
        return new DashboardRecordQuery(parsedDataset, term, code);
    }

    /** Search text as a case-insensitive LIKE pattern with wildcards escaped. */
    String likePattern() {
        if (search == null) {
            return null;
        }
        String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
