package in.gov.slate.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import in.gov.slate.connectors.model.EcModels.EcCertificate;
import in.gov.slate.connectors.model.EcModels.EcEntry;
import in.gov.slate.connectors.model.EcModels.EcSchedule;
import in.gov.slate.rules.EcRuleEngine.Evaluation;
import in.gov.slate.rules.EcRuleEngine.Keywords;
import in.gov.slate.rules.EcRuleEngine.Subject;
import in.gov.slate.rules.EcRuleEngine.SurveyLinkage;

class EcRuleEngineTest {

    private static final Keywords KEYWORDS = new Keywords(Map.of(
            "RECEIPT", List.of("receipt", "discharge"),
            "MORTGAGE", List.of("mortgage", "deposit of title deed"),
            "COURT", List.of("court", "attachment", "decree", "judgment", "order"),
            "COURT_ATTACHMENT", List.of("attachment", "attached"),
            "COURT_ATTACHMENT_EXCLUDE", List.of("raised", "lifted"),
            "COURT_DECREE", List.of("decree", "judgment"),
            "NON_ENCUMBRANCE", List.of("sale", "gift", "release")));
    private static final SurveyLinkage NO_LINK = (survey, sub) -> false;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final Subject PROPERTY = new Subject("KARAPAKKAM", "215", "4");

    private static EcSchedule schedule(String survey, String sub) {
        return new EcSchedule("KARAPAKKAM", survey, sub, null, null, null, null, null);
    }

    private static EcEntry entry(String doc, String date, String nature, String previous, String remarks,
                                 EcSchedule schedule) {
        return new EcEntry("1", LocalDate.parse(date), nature, doc, previous, List.of("A"), List.of("B"),
                null, null, schedule, remarks);
    }

    private static Evaluation evaluate(Subject subject, SurveyLinkage linkage, EcEntry... entries) {
        EcCertificate certificate = new EcCertificate("AVAILABLE", "EC/1", "SRO", TODAY, LocalDate.of(1990, 1, 1),
                TODAY, "FULL", null, List.of(entries), null);
        return EcRuleEngine.evaluate(subject, certificate, linkage, KEYWORDS, TODAY);
    }

    @Test
    void receiptReferencingMortgageNumberAndYearDischargesIt() {
        Evaluation result = evaluate(PROPERTY, NO_LINK,
                entry("181/2016", "2016-04-11", "Mortgage by Deposit of Title Deeds", null, "loan", schedule("215", "4")),
                entry("176/2017", "2017-08-30", "Receipt", "181/2016", "closed", schedule("215", "4")));

        assertThat(result.outcome()).isEqualTo("NO_DISCREPANCY_DETECTED");
        assertThat(result.mortgages()).singleElement()
                .satisfies(m -> {
                    assertThat(m.get("status")).isEqualTo("DISCHARGED");
                    assertThat(m.get("releaseDocumentId")).isEqualTo("176/2017");
                });
    }

    @Test
    void mortgageWithoutMatchingReceiptStaysOpen() {
        Evaluation result = evaluate(PROPERTY, NO_LINK,
                entry("181/2016", "2016-04-11", "Simple Mortgage", null, "loan", schedule("215", "4")),
                entry("176/2017", "2017-08-30", "Receipt", "181/2015", "other loan", schedule("215", "4")));

        assertThat(result.outcome()).isEqualTo("DISCREPANCY_DETECTED");
        assertThat(result.reason()).isEqualTo("OPEN_MORTGAGE");
    }

    @Test
    void receiptRegisteredBeforeTheMortgageDoesNotDischargeIt() {
        Evaluation result = evaluate(PROPERTY, NO_LINK,
                entry("181/2016", "2016-04-11", "Simple Mortgage", null, "loan", schedule("215", "4")),
                entry("12/2015", "2015-01-10", "Receipt", "181/2016", "typo", schedule("215", "4")));

        assertThat(result.reason()).isEqualTo("OPEN_MORTGAGE");
    }

    @Test
    void relatedSurveyNumberWithoutAuthoritativeLinkNeedsReview() {
        Subject property = new Subject("KARAPAKKAM", "100A", null);
        Evaluation result = evaluate(property, NO_LINK,
                entry("300/2004", "2004-05-17", "Simple Mortgage", null, "loan", schedule("100", null)));

        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reason()).isEqualTo("SURVEY_LINK_UNCONFIRMED");
        assertThat(result.mortgages()).isEmpty();
        assertThat(result.entries().get(0).matchStatus()).isEqualTo("LINK_UNCONFIRMED");
    }

    @Test
    void authoritativeLinkTreatsOldSurveyNumberAsTheProperty() {
        Subject property = new Subject("KARAPAKKAM", "100A", null);
        SurveyLinkage linked = (survey, sub) -> "100".equals(survey) && sub == null;
        Evaluation result = evaluate(property, linked,
                entry("300/2004", "2004-05-17", "Simple Mortgage", null, "loan", schedule("100", null)));

        assertThat(result.entries().get(0).matchStatus()).isEqualTo("HISTORICAL_MATCH");
        assertThat(result.reason()).isEqualTo("OPEN_MORTGAGE");
    }

    @Test
    void entryForAnotherPropertyIsIgnored() {
        Evaluation result = evaluate(PROPERTY, NO_LINK,
                entry("2290/2015", "2015-02-03", "Simple Mortgage", null, "loan", schedule("412", "3")));

        assertThat(result.outcome()).isEqualTo("NO_DISCREPANCY_DETECTED");
        assertThat(result.entries().get(0).matchStatus()).isEqualTo("NOT_MATCHED");
    }

    @Test
    void courtEntryWithAttachmentRemarksIsADiscrepancy() {
        Evaluation result = evaluate(PROPERTY, NO_LINK,
                entry("88/2022", "2022-07-05", "Court Order", null,
                        "Attachment before judgment in O.S. 214/2022; the property is attached", schedule("215", "4")));

        assertThat(result.outcome()).isEqualTo("DISCREPANCY_DETECTED");
        assertThat(result.reason()).isEqualTo("COURT_ATTACHMENT");
    }

    @Test
    void attachmentIsNotDecidedFromNatureAlone() {
        Evaluation result = evaluate(PROPERTY, NO_LINK,
                entry("441/2021", "2021-03-01", "Order of Attachment - District Munsif Court", null,
                        "Interim order in a partition suit", schedule("215", "4")));

        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reason()).isEqualTo("COURT_ENTRY_UNCLEAR");
    }

    @Test
    void liftedAttachmentIsNotTreatedAsAnActiveAttachment() {
        assertThat(EcRuleEngine.classify("Court Order", "Attachment lifted by order dated 01-02-2024", KEYWORDS))
                .isEqualTo("COURT_UNCLEAR");
    }

    @Test
    void courtDecreeNeedsReviewButDoesNotBlock() {
        Evaluation result = evaluate(PROPERTY, NO_LINK,
                entry("1021/2019", "2019-12-02", "Court Decree", null, "Final decree in partition suit",
                        schedule("215", "4")));

        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reason()).isEqualTo("COURT_DECREE");
    }

    @Test
    void attachmentOutranksOpenMortgage() {
        Evaluation result = evaluate(PROPERTY, NO_LINK,
                entry("181/2016", "2016-04-11", "Simple Mortgage", null, "loan", schedule("215", "4")),
                entry("88/2022", "2022-07-05", "Court Order", null, "Property attached", schedule("215", "4")));

        assertThat(result.reason()).isEqualTo("COURT_ATTACHMENT");
    }

    @Test
    void compositeSurveyNumberMatchesSurveyPlusSubdivision() {
        Subject property = new Subject("KARAPAKKAM", "45/2", null);
        assertThat(EcRuleEngine.matchSchedule(property, schedule("45", "2"), NO_LINK)).isEqualTo("MATCH");
        assertThat(EcRuleEngine.matchSchedule(property, schedule("45", "3"), NO_LINK)).isEqualTo("LINK_UNCONFIRMED");
        assertThat(EcRuleEngine.matchSchedule(property, schedule("46", null), NO_LINK)).isEqualTo("NOT_MATCHED");
    }
}
