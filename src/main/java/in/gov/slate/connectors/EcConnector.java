package in.gov.slate.connectors;

import in.gov.slate.connectors.model.EcModels.EcCertificate;
import in.gov.slate.connectors.model.EcModels.EcRequest;

public interface EcConnector {
    /** Returns null when the source registry did not answer at all. */
    EcCertificate fetch(EcRequest request);
}
