package in.gov.slate.connectors;

import in.gov.slate.connectors.model.RevenueModels.MutationPushRequest;
import in.gov.slate.connectors.model.RevenueModels.MutationPushResponse;
import in.gov.slate.connectors.model.RevenueModels.RevenueLookupRequest;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwnershipResponse;

public interface RevenueConnector {

    RevenueOwnershipResponse lookup(RevenueLookupRequest request);

    MutationPushResponse pushMutation(MutationPushRequest request);
}
