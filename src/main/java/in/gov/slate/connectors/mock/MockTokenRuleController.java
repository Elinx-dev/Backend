package in.gov.slate.connectors.mock;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.chain.TokenRuleConnector.RuleFacts;

/** Sets mock court-case / tax-dues flags so the transfer rule check can be demonstrated. Local phase only. */
@RestController
@RequestMapping("/mock/token-rules")
@ConditionalOnProperty(name = "slate.connectors.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockTokenRuleController {

    private final MockTokenRuleConnector rules;

    public MockTokenRuleController(MockTokenRuleConnector rules) {
        this.rules = rules;
    }

    public record Flags(Boolean courtCase, String courtCaseRef, BigDecimal taxDues) {
    }

    @GetMapping
    public Map<String, RuleFacts> all() {
        return rules.all();
    }

    @PutMapping
    public RuleFacts set(@RequestParam String propertyRef, @RequestBody Flags flags) {
        rules.set(propertyRef, Boolean.TRUE.equals(flags.courtCase()), flags.courtCaseRef(), flags.taxDues());
        return rules.facts(propertyRef);
    }

    @DeleteMapping
    public Map<String, String> clear(@RequestParam String propertyRef) {
        rules.clear(propertyRef);
        return Map.of("propertyRef", propertyRef, "status", "CLEARED");
    }
}
