package in.gov.slate.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.gov.slate.common.ApiException;

/**
 * Which overall outcomes of a rule engine may proceed past the Rule checks stage, plus reason codes
 * that always stop the transaction even when their outcome is allowed.
 */
public record RuleOutcomePolicy(Set<String> allowedOutcomes, Set<String> blockingReasonCodes) {

    public static final List<String> OUTCOMES = List.of(
            "NO_DISCREPANCY_DETECTED", "NOT_CHECKED", "REVIEW_REQUIRED", "DISCREPANCY_DETECTED");

    public static final RuleOutcomePolicy DEFAULT =
            new RuleOutcomePolicy(Set.of("NO_DISCREPANCY_DETECTED", "NOT_CHECKED"), Set.of());

    public static final Map<String, List<String>> REASON_CODES = Map.of(
            "EC", List.of("EC_LOOKBACK_NOT_CONFIGURED", "SURVEY_NO_MISSING", "EC_DATA_UNAVAILABLE",
                    "COURT_ATTACHMENT", "OPEN_MORTGAGE", "COURT_DECREE", "COURT_ENTRY_UNCLEAR",
                    "SURVEY_LINK_UNCONFIRMED", "UNCLASSIFIED_ENTRY", "PARTIAL_COVERAGE"),
            "REVENUE_OWNERSHIP", List.of("REVENUE_DATA_UNAVAILABLE", "REVENUE_RESPONSE_UNUSABLE",
                    "PROPERTY_DATA_MISSING", "OWNER_SIDE_NOT_CONFIGURED", "OWNER_SIDE_PARTIES_MISSING",
                    "PROPERTY_IDENTITY_MISMATCH", "REVENUE_RECORD_NOT_FOUND", "SURVEY_SET_MISMATCH",
                    "OWNER_MISMATCH", "EXTENT_MISMATCH", "MULTIPLE_REVENUE_RECORDS", "IDENTITY_UNCLEAR",
                    "SURVEY_LINK_UNCONFIRMED", "EXTENT_MISSING", "EXTENT_UNCONVERTIBLE"));

    public RuleOutcomePolicy {
        Set<String> allowed = new LinkedHashSet<>();
        OUTCOMES.stream().filter(allowedOutcomes::contains).forEach(allowed::add);
        allowedOutcomes = Collections.unmodifiableSet(allowed);
        blockingReasonCodes = Collections.unmodifiableSet(new LinkedHashSet<>(blockingReasonCodes));
    }

    public boolean blocks(String outcome, String reasonCode) {
        return !allowedOutcomes.contains(outcome)
                || (reasonCode != null && blockingReasonCodes.contains(reasonCode));
    }

    public Map<String, Object> view(String engine) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", engine);
        out.put("allowedOutcomes", List.copyOf(allowedOutcomes));
        out.put("blockingReasonCodes", List.copyOf(blockingReasonCodes));
        return out;
    }

    public static RuleOutcomePolicy validated(List<String> allowedOutcomes, List<String> blockingReasonCodes) {
        List<String> allowed = allowedOutcomes == null ? List.of() : allowedOutcomes;
        List<String> blocking = blockingReasonCodes == null ? List.of() : blockingReasonCodes;
        for (String outcome : allowed) {
            if (!OUTCOMES.contains(outcome)) {
                throw ApiException.badRequest("Unknown rule check outcome " + outcome);
            }
        }
        if (!allowed.contains("NO_DISCREPANCY_DETECTED")) {
            throw ApiException.badRequest("NO_DISCREPANCY_DETECTED must always be allowed to proceed");
        }
        for (String code : blocking) {
            if (code == null || !code.matches("[A-Z][A-Z_]{1,63}")) {
                throw ApiException.badRequest("Invalid reason code " + code);
            }
        }
        return new RuleOutcomePolicy(new LinkedHashSet<>(allowed), new LinkedHashSet<>(blocking));
    }

    public static Map<String, Object> catalog() {
        return Map.of("outcomes", OUTCOMES, "reasonCodes", REASON_CODES);
    }
}
