package in.gov.slate.connectors.model;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class RevenueModels {

    private RevenueModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RevenueOwner(String name, String relationType, String relatedPersonName, BigDecimal sharePct) {
    }

    /** responseStatus is FOUND, NOT_FOUND, MULTIPLE or UNAVAILABLE. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RevenueOwnershipResponse(String responseStatus, String recordNumber, String village,
                                           String surveyNo, String subdivisionNo, String landType,
                                           String classification, BigDecimal extent, String extentUnit,
                                           List<RevenueOwner> owners, List<String> multipleRecordRefs,
                                           String sourceReference) {
    }

    public record RevenueLookupRequest(String district, String taluk, String village, String surveyNo,
                                       String subdivisionNo, String landType) {
    }

    public record MutationPushRequest(String propertyRef, String registeredDocumentNo, String mutationType,
                                      List<String> newOwners) {
    }

    public record MutationPushResponse(String status, String mutationNumber, String revenueRecordRef) {
    }
}
