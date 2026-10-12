package in.gov.slate.connectors.mock;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import in.gov.slate.chain.TokenRuleConnector;

/**
 * Mock court-case and tax-dues source for the pre-transfer rule check. Every property is clean unless a
 * flag is set through /mock/token-rules, so results are deterministic for demos and tests.
 */
@Component
@ConditionalOnProperty(name = "slate.connectors.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockTokenRuleConnector implements TokenRuleConnector {

    private final Map<String, RuleFacts> flags = new ConcurrentHashMap<>();

    @Override
    public RuleFacts facts(String propertyRef) {
        return flags.getOrDefault(propertyRef, new RuleFacts(false, null, BigDecimal.ZERO, "MOCK"));
    }

    public void set(String propertyRef, boolean courtCase, String courtCaseRef, BigDecimal taxDues) {
        flags.put(propertyRef, new RuleFacts(courtCase, courtCaseRef,
                taxDues == null ? BigDecimal.ZERO : taxDues, "MOCK"));
    }

    public void clear(String propertyRef) {
        flags.remove(propertyRef);
    }

    public Map<String, RuleFacts> all() {
        return Map.copyOf(flags);
    }
}
