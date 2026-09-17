package in.gov.slate.transaction;

import in.gov.slate.common.ApiException;

/**
 * surveyRequired is derived from the deed type's configured survey rule and the
 * transfer scope. It is never entered by an officer.
 */
public final class SurveyRequirement {

    public static final String PHYSICAL_PARTIAL = "PHYSICAL_PARTIAL_EXTENT_SUBDIVISION";

    private SurveyRequirement() {
    }

    public static boolean derive(String surveyRule, String transferScope) {
        return switch (surveyRule) {
            case "ALWAYS" -> true;
            case "NEVER" -> false;
            case "DERIVED_FROM_TRANSFER_SCOPE" -> PHYSICAL_PARTIAL.equals(transferScope);
            default -> throw ApiException.badRequest("Unknown survey rule " + surveyRule);
        };
    }
}
