package in.gov.slate.connectors.mock;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import in.gov.slate.connectors.MockFixtureStore;
import in.gov.slate.connectors.RevenueConnector;
import in.gov.slate.connectors.model.RevenueModels.MutationPushRequest;
import in.gov.slate.connectors.model.RevenueModels.MutationPushResponse;
import in.gov.slate.connectors.model.RevenueModels.RevenueLookupRequest;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwnershipResponse;

@Component
@ConditionalOnProperty(name = "slate.connectors.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockRevenueConnector implements RevenueConnector {

    private final MockFixtureStore fixtures;
    private final AtomicInteger mutationSequence = new AtomicInteger(4500);

    public MockRevenueConnector(MockFixtureStore fixtures) {
        this.fixtures = fixtures;
    }

    @Override
    public RevenueOwnershipResponse lookup(RevenueLookupRequest request) {
        RevenueOwnershipResponse response =
                fixtures.revenue(request.village(), request.surveyNo(), request.subdivisionNo());
        if (response == null) {
            // An unseeded parcel is a genuine "no record", not an outage.
            return new RevenueOwnershipResponse("NOT_FOUND", null, request.village(), request.surveyNo(),
                    request.subdivisionNo(), request.landType(), null, null, null, java.util.List.of(), null,
                    "MOCK-REVENUE-API/NO-FIXTURE");
        }
        return response;
    }

    @Override
    public MutationPushResponse pushMutation(MutationPushRequest request) {
        return new MutationPushResponse("ACCEPTED",
                "MUT/" + mutationSequence.incrementAndGet() + "/2026",
                "PATTA-" + Math.abs(request.propertyRef().hashCode() % 10000));
    }
}
