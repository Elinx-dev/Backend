package in.gov.slate.rules;

import java.time.LocalDate;
import java.util.Map;

import in.gov.slate.transaction.TransactionContext;

public interface RuleEngine {

    /** Outcome vocabulary is fixed by the specification; findings are advisory only. */
    record Outcome(String overallOutcome, String reasonCode, Map<String, Object> payload) {
    }

    String engine();

    Outcome run(TransactionContext ctx, long requestId, LocalDate assessmentDate, String mode);
}
