package in.gov.slate.connectors;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.connectors.model.EcModels.EcCertificate;
import in.gov.slate.connectors.model.RevenueModels.RevenueOwnershipResponse;

/**
 * Loads the deterministic fixtures used by the mock external APIs. Keys are
 * VILLAGE|SURVEY_NO|SUBDIVISION_NO so a lookup is reproducible across restarts.
 */
@Component
public class MockFixtureStore {

    private final Map<String, EcCertificate> ecFixtures;
    private final Map<String, RevenueOwnershipResponse> revenueFixtures;

    public MockFixtureStore(ObjectMapper mapper) throws IOException {
        this.ecFixtures = load(mapper, "mock/ec-fixtures.json", new TypeReference<EcCertificate>() {
        });
        this.revenueFixtures = load(mapper, "mock/revenue-fixtures.json",
                new TypeReference<RevenueOwnershipResponse>() {
                });
    }

    /** Keys starting with an underscore document the fixture file and are not lookups. */
    private <T> Map<String, T> load(ObjectMapper mapper, String path, TypeReference<T> type) throws IOException {
        Map<String, Object> raw = mapper.readValue(new ClassPathResource(path).getInputStream(),
                new TypeReference<Map<String, Object>>() {
                });
        Map<String, T> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey().startsWith("_")) {
                continue;
            }
            parsed.put(entry.getKey(), mapper.convertValue(entry.getValue(), type));
        }
        return parsed;
    }

    public static String key(String village, String surveyNo, String subdivisionNo) {
        return (village == null ? "" : village.toUpperCase()) + "|"
                + (surveyNo == null ? "" : surveyNo) + "|"
                + (subdivisionNo == null ? "" : subdivisionNo);
    }

    public EcCertificate ec(String village, String surveyNo, String subdivisionNo) {
        return ecFixtures.get(key(village, surveyNo, subdivisionNo));
    }

    public RevenueOwnershipResponse revenue(String village, String surveyNo, String subdivisionNo) {
        return revenueFixtures.get(key(village, surveyNo, subdivisionNo));
    }

    public Map<String, EcCertificate> allEc() {
        return ecFixtures;
    }

    public Map<String, RevenueOwnershipResponse> allRevenue() {
        return revenueFixtures;
    }
}
