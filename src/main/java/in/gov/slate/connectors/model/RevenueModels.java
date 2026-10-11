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

    /** One survey/subdivision row of a Revenue record, with its own extent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RevenueParcel(String surveyNo, String subdivisionNo, BigDecimal extent, String extentUnit) {
    }

    /**
     * responseStatus is FOUND, NOT_FOUND, MULTIPLE or UNAVAILABLE. A record may cover several
     * survey/subdivision rows ({@code parcels}); older single-parcel responses carry them in
     * surveyNo/subdivisionNo/extent instead.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RevenueOwnershipResponse(String responseStatus, String recordNumber, String village,
                                           String surveyNo, String subdivisionNo, String landType,
                                           String classification, BigDecimal extent, String extentUnit,
                                           List<RevenueOwner> owners, List<String> multipleRecordRefs,
                                           String sourceReference, String district, String taluk,
                                           List<RevenueParcel> parcels) {

        public List<RevenueParcel> allParcels() {
            if (parcels != null && !parcels.isEmpty()) {
                return parcels;
            }
            return surveyNo == null ? List.of() : List.of(new RevenueParcel(surveyNo, subdivisionNo, extent, extentUnit));
        }
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
