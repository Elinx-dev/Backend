package in.gov.slate.connectors.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class EcModels {

    private EcModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EcSchedule(String village, String surveyNo, String subdivisionNo, BigDecimal extent,
                             String extentUnit, String doorNo, String plotNo, String boundaries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EcEntry(String entryNo, LocalDate entryDate, String nature, String documentNo,
                          String previousDocumentReference, List<String> executants, List<String> claimants,
                          BigDecimal amount, String lender, EcSchedule schedule, String remarks) {
    }

    /** coverageStatus is FULL, PARTIAL or NONE; responseStatus is AVAILABLE or UNAVAILABLE. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EcCertificate(String responseStatus, String certificateNo, String issuedBy, LocalDate issuedOn,
                                LocalDate periodFrom, LocalDate periodTo, String coverageStatus,
                                String coverageNote, List<EcEntry> entries) {
    }

    public record EcRequest(String village, String surveyNo, String subdivisionNo, LocalDate searchFrom,
                            LocalDate searchTo) {
    }
}
