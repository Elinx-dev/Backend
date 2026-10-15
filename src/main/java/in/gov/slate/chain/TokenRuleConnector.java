package in.gov.slate.chain;

import java.math.BigDecimal;

/** Pre-transfer rule source (court cases, land-revenue dues). A mock implementation serves this local phase. */
public interface TokenRuleConnector {

    record RuleFacts(boolean courtCase, String courtCaseRef, BigDecimal taxDues, String source) {
    }

    RuleFacts facts(String propertyRef);
}
