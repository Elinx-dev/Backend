package in.gov.slate.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import in.gov.slate.common.ApiException;

class TransactionTypeRulesTest {

    private static final Map<String, Object> SALE = Map.of("code", "SALE", "first_party_label", "Vendor",
            "second_party_label", "Vendee", "subdivision_allowed", true, "individuals_only", false,
            "blood_relation_required", false);
    private static final Map<String, Object> RELEASE = Map.of("code", "RELEASE", "first_party_label", "Releasor",
            "second_party_label", "Releasee", "subdivision_allowed", false, "individuals_only", false,
            "blood_relation_required", false);
    private static final Map<String, Object> SETTLEMENT = Map.of("code", "SETTLEMENT",
            "first_party_label", "Settler", "second_party_label", "Settlee", "subdivision_allowed", true,
            "individuals_only", true, "blood_relation_required", true);

    private static TransactionService.PartyInput party(String side, String type, String relationship) {
        return new TransactionService.PartyInput(side, null, type, side + " name", null, null, null, null, null,
                relationship, null, null, null, null, null, null);
    }

    @Test
    void subdivisionMakesSurveyMandatoryAndPartialScope() {
        var decision = TransactionTypeRules.decide(SALE, true, false, "FULL_PROPERTY", "INDIVIDUAL");
        assertThat(decision.surveyRequired()).isTrue();
        assertThat(decision.transferScope()).isEqualTo(SurveyRequirement.PHYSICAL_PARTIAL);
    }

    @Test
    void partyRequestedSurveyIsSeparateFromSubdivision() {
        var decision = TransactionTypeRules.decide(RELEASE, false, true, "UNDIVIDED_SHARE", null);
        assertThat(decision.subdivisionRequired()).isFalse();
        assertThat(decision.surveyRequired()).isTrue();
        assertThat(decision.transferScope()).isEqualTo("UNDIVIDED_SHARE");
    }

    @Test
    void noSubdivisionAndNoPartyRequestMeansNoSurvey() {
        var decision = TransactionTypeRules.decide(SALE, false, false, null, null);
        assertThat(decision.surveyRequired()).isFalse();
        assertThat(decision.transferScope()).isEqualTo("FULL_PROPERTY");
    }

    @Test
    void releaseRejectsSubdivision() {
        assertThatThrownBy(() -> TransactionTypeRules.decide(RELEASE, true, false, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("Subdivision is not available for RELEASE");
    }

    @Test
    void partialScopeWithoutSubdivisionIsRejected() {
        assertThatThrownBy(() -> TransactionTypeRules.decide(SALE, false, false,
                SurveyRequirement.PHYSICAL_PARTIAL, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void settlementOnlyForIndividualOwners() {
        assertThatThrownBy(() -> TransactionTypeRules.decide(SETTLEMENT, false, false, null,
                "PRIVATE_LIMITED_COMPANY")).isInstanceOf(ApiException.class)
                .hasMessageContaining("only available for properties owned by individuals");
    }

    @Test
    void settlementNeedsSettlerSettleeAndActiveBloodRelation() {
        Set<String> active = Set.of("SON", "DAUGHTER");
        assertThatThrownBy(() -> TransactionTypeRules.validateParties(SETTLEMENT,
                List.of(party("SIDE_2", "INDIVIDUAL", "SON")), active::contains))
                .hasMessageContaining("At least one Settler");
        assertThatThrownBy(() -> TransactionTypeRules.validateParties(SETTLEMENT,
                List.of(party("SIDE_1", "INDIVIDUAL", null)), active::contains))
                .hasMessageContaining("At least one Settlee");
        assertThatThrownBy(() -> TransactionTypeRules.validateParties(SETTLEMENT,
                List.of(party("SIDE_1", "INDIVIDUAL", null), party("SIDE_2", "INDIVIDUAL", null)), active::contains))
                .hasMessageContaining("Select the relationship");
        assertThatThrownBy(() -> TransactionTypeRules.validateParties(SETTLEMENT,
                List.of(party("SIDE_1", "INDIVIDUAL", null), party("SIDE_2", "INDIVIDUAL", "UNRELATED")),
                active::contains)).hasMessageContaining("not an active blood relation");
        assertThatThrownBy(() -> TransactionTypeRules.validateParties(SETTLEMENT,
                List.of(party("SIDE_1", "HUF", null), party("SIDE_2", "INDIVIDUAL", "SON")), active::contains))
                .hasMessageContaining("only allowed between individuals");
        TransactionTypeRules.validateParties(SETTLEMENT,
                List.of(party("SIDE_1", "INDIVIDUAL", null), party("SIDE_2", "INDIVIDUAL", "SON")), active::contains);
    }

    @Test
    void legacyDeedTypesSkipPartyChecks() {
        TransactionTypeRules.validateParties(Map.of("code", "SALE_FULL"), List.of(), code -> false);
    }
}
