package in.gov.slate.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import in.gov.slate.connectors.model.RevenueModels.RevenueOwner;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwnershipResponse;
import in.gov.slate.connectors.model.RevenueModels.RevenueParcel;
import in.gov.slate.rules.RevenueOwnershipEngine.Evaluation;
import in.gov.slate.rules.RevenueOwnershipEngine.Lookup;
import in.gov.slate.rules.RevenueOwnershipEngine.SlateParcel;
import in.gov.slate.rules.RevenueOwnershipEngine.Subject;
import in.gov.slate.rules.RevenueOwnershipEngine.SurveyLinkage;
import in.gov.slate.transaction.TransactionContext;

class RevenueOwnershipEngineTest {

    private static final SurveyLinkage NO_LINK = (a, b, c, d) -> false;

    private static SlateParcel slate(String survey, String sub, String extent, String unit) {
        return new SlateParcel(survey, sub, extent == null ? null : new BigDecimal(extent), unit);
    }

    private static RevenueParcel rev(String survey, String sub, String extent, String unit) {
        return new RevenueParcel(survey, sub, extent == null ? null : new BigDecimal(extent), unit);
    }

    private static Subject subject(SlateParcel... parcels) {
        return new Subject("CHN", "SHOL", "KARAPAKKAM", "RURAL", List.of(parcels));
    }

    private static RevenueOwnershipResponse record(String status, List<RevenueParcel> parcels, String... owners) {
        return new RevenueOwnershipResponse(status, "PATTA-1", "KARAPAKKAM", null, null, "RURAL", "DRY", null, null,
                Arrays.stream(owners).map(n -> new RevenueOwner(n, null, null, null)).toList(), null, "REF/1",
                "CHN", "SHOL", parcels);
    }

    /** Every SLATE survey row is looked up; each returns the same patta. */
    private static Evaluation evaluate(Subject subject, List<String> owners, RevenueOwnershipResponse response,
                                       SurveyLinkage linkage) {
        List<Lookup> lookups = subject.parcels().stream().map(p -> new Lookup(p, response, false)).toList();
        return RevenueOwnershipEngine.evaluate(subject, owners, lookups, linkage);
    }

    private static final Subject THREE_ROWS = subject(slate("100", "1A", "50", "CENT"),
            slate("100", "1B", "25", "CENT"), slate("101", "2", "1", "ACRE"));

    @Test
    void unorderedSurveySetOwnersAndConvertedExtentsMatch() {
        Evaluation result = evaluate(THREE_ROWS, List.of("RAMESH KUMAR", "LAKSHMI RAMESH"),
                record("FOUND", List.of(rev("101", "2", "43560", "SQ_FT"), rev("100", "1A", "0.50", "ACRE"),
                        rev("100", "1B", "10890", "SQ_FT")), "Lakshmi Ramesh", "RAMESH  KUMAR"), NO_LINK);

        assertThat(result.outcome()).isEqualTo("NO_DISCREPANCY_DETECTED");
        assertThat(result.details()).containsEntry("propertyResult", "PROPERTY_MATCH")
                .containsEntry("ownerResult", "OWNER_SET_MATCH")
                .containsEntry("extentResult", "EXTENT_SET_MATCH");
    }

    @Test
    void differentSurveyIsAClearSurveySetMismatch() {
        Evaluation result = evaluate(THREE_ROWS, List.of("RAMESH KUMAR"),
                record("FOUND", List.of(rev("100", "1A", "50", "CENT"), rev("100", "1B", "25", "CENT"),
                        rev("102", "2", "1", "ACRE")), "RAMESH KUMAR"), NO_LINK);

        assertThat(result.outcome()).isEqualTo("DISCREPANCY_DETECTED");
        assertThat(result.reason()).isEqualTo("SURVEY_SET_MISMATCH");
        assertThat(result.details()).containsEntry("propertyResult", "PROPERTY_MISMATCH");
    }

    @Test
    void relatedSurveyWithoutAuthoritativeLinkNeedsReview() {
        Evaluation result = evaluate(subject(slate("100", null, "20", "CENT")), List.of("PRIYA SEKAR"),
                record("FOUND", List.of(rev("100A", null, "20", "CENT")), "PRIYA SEKAR"), NO_LINK);

        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reason()).isEqualTo("SURVEY_LINK_UNCONFIRMED");
    }

    @Test
    void authoritativeLinkTreatsOldNumberAsTheSameParcel() {
        Evaluation result = evaluate(subject(slate("100", null, "20", "CENT")), List.of("PRIYA SEKAR"),
                record("FOUND", List.of(rev("100A", null, "20", "CENT")), "PRIYA SEKAR"),
                (a, aSub, b, bSub) -> "100".equals(a) && "100A".equals(b));

        assertThat(result.outcome()).isEqualTo("NO_DISCREPANCY_DETECTED");
    }

    @Test
    void initialsOrShortenedNamesAreAnOwnerMismatch() {
        for (String revenueName : List.of("R KUMAR", "RAMESH K")) {
            Evaluation result = evaluate(subject(slate("420", "1", "1", "ACRE")), List.of("RAMESH KUMAR"),
                    record("FOUND", List.of(rev("420", "1", "100", "CENT")), revenueName), NO_LINK);

            assertThat(result.outcome()).isEqualTo("DISCREPANCY_DETECTED");
            assertThat(result.reason()).isEqualTo("OWNER_MISMATCH");
        }
    }

    @Test
    void differentOwnerSetIsAMismatchEvenWhenOneOwnerMatches() {
        Evaluation result = evaluate(subject(slate("410", "3", "10", "CENT")), List.of("OWNER A", "OWNER C"),
                record("FOUND", List.of(rev("410", "3", "4356", "SQ_FT")), "OWNER B", "OWNER A"), NO_LINK);

        assertThat(result.reason()).isEqualTo("OWNER_MISMATCH");
        assertThat(result.details()).containsEntry("ownerResult", "OWNER_MISMATCH");
    }

    @Test
    void surveyWiseExtentMismatchIsADiscrepancyEvenIfTotalsAgree() {
        Evaluation result = evaluate(subject(slate("430", "1A", "50", "CENT"), slate("430", "1B", "30", "CENT")),
                List.of("ANITHA DEVI"),
                record("FOUND", List.of(rev("430", "1A", "30", "CENT"), rev("430", "1B", "0.50", "ACRE")),
                        "ANITHA DEVI"), NO_LINK);

        assertThat(result.outcome()).isEqualTo("DISCREPANCY_DETECTED");
        assertThat(result.reason()).isEqualTo("EXTENT_MISMATCH");
    }

    @Test
    void missingOrUnconvertibleExtentNeedsReview() {
        Evaluation missing = evaluate(subject(slate("460", "1", "10", "CENT")), List.of("GEETHA MANI"),
                record("FOUND", List.of(rev("460", "1", null, null)), "GEETHA MANI"), NO_LINK);
        Evaluation unknownUnit = evaluate(subject(slate("460", "1", "10", "CENT")), List.of("GEETHA MANI"),
                record("FOUND", List.of(rev("460", "1", "2", "GROUND")), "GEETHA MANI"), NO_LINK);

        assertThat(missing.reason()).isEqualTo("EXTENT_MISSING");
        assertThat(missing.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(unknownUnit.reason()).isEqualTo("EXTENT_UNCONVERTIBLE");
        assertThat(unknownUnit.outcome()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void identityFieldsMustMatch() {
        RevenueOwnershipResponse otherTaluk = new RevenueOwnershipResponse("FOUND", "PATTA-1", "KARAPAKKAM", null,
                null, "NATHAM", null, null, null, List.of(new RevenueOwner("A", null, null, null)), null, null,
                "CHN", "SHOL", List.of(rev("1", null, "1", "ACRE")));
        Evaluation result = evaluate(subject(slate("1", null, "1", "ACRE")), List.of("A"), otherTaluk, NO_LINK);

        assertThat(result.reason()).isEqualTo("PROPERTY_IDENTITY_MISMATCH");
    }

    @Test
    void multipleRevenueRecordsNeedReview() {
        Evaluation result = evaluate(subject(slate("90", "4", "1", "ACRE")), List.of("A"),
                record("MULTIPLE", List.of(), "A"), NO_LINK);

        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reason()).isEqualTo("MULTIPLE_REVENUE_RECORDS");
    }

    @Test
    void unavailableRevenueIsNotChecked() {
        Evaluation down = evaluate(subject(slate("54", "7", "1", "ACRE")), List.of("A"), null, NO_LINK);
        Evaluation noOwners = evaluate(subject(slate("54", "7", "1", "ACRE")), List.of("A"),
                record("FOUND", List.of(rev("54", "7", "1", "ACRE"))), NO_LINK);

        assertThat(down.outcome()).isEqualTo("NOT_CHECKED");
        assertThat(down.reason()).isEqualTo("REVENUE_DATA_UNAVAILABLE");
        assertThat(noOwners.reason()).isEqualTo("REVENUE_RESPONSE_UNUSABLE");
    }

    @Test
    void noRevenueRecordForAnySurveyIsADiscrepancy() {
        Evaluation result = evaluate(subject(slate("999", null, "1", "ACRE")), List.of("A"),
                record("NOT_FOUND", List.of()), NO_LINK);

        assertThat(result.reason()).isEqualTo("REVENUE_RECORD_NOT_FOUND");
    }

    @Test
    void missingPropertyDataOrUnsupportedLandTypeIsNotChecked() {
        assertThat(RevenueOwnershipEngine.missingPropertyData(
                new Subject("CHN", "SHOL", "KARAPAKKAM", null, List.of(slate("1", null, "1", "ACRE"))),
                List.of("RURAL", "NATHAM"))).contains("no land type");
        assertThat(RevenueOwnershipEngine.missingPropertyData(
                new Subject("CHN", "SHOL", "KARAPAKKAM", "URBAN", List.of(slate("1", null, "1", "ACRE"))),
                List.of("RURAL", "NATHAM"))).contains("not covered");
        assertThat(RevenueOwnershipEngine.missingPropertyData(subject(), List.of("RURAL"))).contains("survey");
        assertThat(RevenueOwnershipEngine.missingPropertyData(subject(slate("1", null, "1", "ACRE")),
                List.of("RURAL"))).isNull();
    }

    @Test
    void ownerSideComesFromTheTransactionTypeAndNeedsVerifiedAadhaar() {
        List<Map<String, Object>> parties = List.of(
                Map.of("id", 1L, "side", "SIDE_1", "name", "RAMESH KUMAR", "aadhaar_captured", true),
                Map.of("id", 2L, "side", "SIDE_2", "name", "BUYER", "aadhaar_captured", true));
        TransactionContext pending = context(Map.of("owner_side", "SIDE_1"), parties, List.of());
        TransactionContext verified = context(Map.of("owner_side", "SIDE_1"), parties,
                List.of(Map.of("party_id", 1L, "status", "VERIFIED")));
        TransactionContext unconfigured = context(Map.of(), parties, List.of());

        assertThat(RevenueOwnershipEngine.ownerSide(pending).problemReason()).isEqualTo("AADHAAR_VERIFICATION_PENDING");
        assertThat(RevenueOwnershipEngine.ownerSide(verified).names()).containsExactly("RAMESH KUMAR");
        assertThat(RevenueOwnershipEngine.ownerSide(unconfigured).problemReason()).isEqualTo("OWNER_SIDE_NOT_CONFIGURED");
    }

    private static TransactionContext context(Map<String, Object> deedType, List<Map<String, Object>> parties,
                                              List<Map<String, Object>> consents) {
        return new TransactionContext(Map.of("deed_type_code", "SALE"), Map.of(), deedType, parties, List.of(),
                consents, List.of(), null, List.of(), List.of(), List.of());
    }

    @Test
    void omitsUnsupportedLandTypeFromLegacySnapshotColumn() {
        assertThat(RevenueOwnershipEngine.snapshotLandType("URBAN")).isNull();
        assertThat(RevenueOwnershipEngine.snapshotLandType("RURAL")).isEqualTo("RURAL");
        assertThat(RevenueOwnershipEngine.snapshotLandType("NATHAM")).isEqualTo("NATHAM");
    }
}
