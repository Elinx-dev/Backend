package in.gov.slate.common;

import java.util.List;

public class ValidationException extends RuntimeException {

    public record Violation(String ruleCode, String scope, String severity, String message) {
    }

    private final transient List<Violation> violations;

    public ValidationException(List<Violation> violations) {
        super(violations.size() + " validation rule(s) failed");
        this.violations = violations;
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
