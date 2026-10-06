package in.gov.slate.transaction;

import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

import in.gov.slate.common.ApiException;

/**
 * Rules driven by the Transaction Type Master: subdivision availability, the
 * survey decision, individuals-only types and blood-relation checks.
 */
public final class TransactionTypeRules {

    public static final String FULL_PROPERTY = "FULL_PROPERTY";

    private TransactionTypeRules() {
    }

    public record Decision(boolean subdivisionRequired, boolean surveyRequiredByParty, boolean surveyRequired,
                           String transferScope) {
    }

    /** Subdivision Required = Yes makes survey mandatory; else the party may still ask for one. */
    public static boolean surveyRequired(boolean subdivisionRequired, boolean surveyRequiredByParty) {
        return subdivisionRequired || surveyRequiredByParty;
    }

    public static Decision decide(Map<String, Object> transactionType, Boolean subdivisionRequired,
                                  Boolean surveyRequiredByParty, String explicitScope, String propertyOwnerType) {
        String code = (String) transactionType.get("code");
        boolean subdivision = Boolean.TRUE.equals(subdivisionRequired);
        boolean byParty = Boolean.TRUE.equals(surveyRequiredByParty);

        if (subdivision && !Boolean.TRUE.equals(transactionType.get("subdivision_allowed"))) {
            throw ApiException.badRequest("Subdivision is not available for " + code);
        }
        if (Boolean.TRUE.equals(transactionType.get("individuals_only"))
                && propertyOwnerType != null && !"INDIVIDUAL".equals(propertyOwnerType)) {
            throw ApiException.badRequest(code + " is only available for properties owned by individuals");
        }

        String scope;
        if (subdivision) {
            scope = SurveyRequirement.PHYSICAL_PARTIAL;
        } else if (explicitScope == null || explicitScope.isBlank()) {
            scope = FULL_PROPERTY;
        } else if (SurveyRequirement.PHYSICAL_PARTIAL.equals(explicitScope)) {
            throw ApiException.badRequest("A partial extent transfer needs Subdivision Required = Yes");
        } else {
            scope = explicitScope;
        }
        return new Decision(subdivision, byParty, surveyRequired(subdivision, byParty), scope);
    }

    /**
     * Both sides must be present; individuals-only types accept only individual parties;
     * blood-relation types need an active Blood Relation Master code on every second party.
     */
    public static void validateParties(Map<String, Object> deedType, Collection<TransactionService.PartyInput> parties,
                                       Predicate<String> isActiveBloodRelation) {
        if (deedType.get("first_party_label") == null) {
            return;
        }
        String code = (String) deedType.get("code");
        String first = (String) deedType.get("first_party_label");
        String second = (String) deedType.get("second_party_label");
        if (parties.stream().noneMatch(p -> "SIDE_1".equals(p.side()))) {
            throw ApiException.badRequest("At least one " + first + " must be selected");
        }
        if (parties.stream().noneMatch(p -> "SIDE_2".equals(p.side()))) {
            throw ApiException.badRequest("At least one " + second + " must be selected");
        }
        if (Boolean.TRUE.equals(deedType.get("individuals_only"))
                && parties.stream().anyMatch(p -> !"INDIVIDUAL".equals(p.partyType())
                        || (p.ownerTypeCode() != null && !"INDIVIDUAL".equals(p.ownerTypeCode())))) {
            throw ApiException.badRequest(code + " is only allowed between individuals");
        }
        if (Boolean.TRUE.equals(deedType.get("blood_relation_required"))) {
            for (TransactionService.PartyInput p : parties) {
                if (!"SIDE_2".equals(p.side())) {
                    continue;
                }
                if (p.relationshipCode() == null || p.relationshipCode().isBlank()) {
                    throw ApiException.badRequest("Select the relationship of " + second + " " + p.name()
                            + " to the " + first);
                }
                if (!isActiveBloodRelation.test(p.relationshipCode())) {
                    throw ApiException.badRequest("Relationship " + p.relationshipCode()
                            + " is not an active blood relation; " + code + " cannot proceed");
                }
            }
        }
    }
}
