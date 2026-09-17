package in.gov.slate.connectors.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import in.gov.slate.connectors.EcConnector;
import in.gov.slate.connectors.MockFixtureStore;
import in.gov.slate.connectors.model.EcModels.EcCertificate;
import in.gov.slate.connectors.model.EcModels.EcRequest;

@Component
@ConditionalOnProperty(name = "slate.connectors.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockEcConnector implements EcConnector {

    private final MockFixtureStore fixtures;

    public MockEcConnector(MockFixtureStore fixtures) {
        this.fixtures = fixtures;
    }

    @Override
    public EcCertificate fetch(EcRequest request) {
        return fixtures.ec(request.village(), request.surveyNo(), request.subdivisionNo());
    }
}
