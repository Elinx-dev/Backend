package in.gov.slate.rules;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import in.gov.slate.transaction.TransactionContext;

public interface RuleEngine {

    /** Outcome vocabulary is fixed by the specification; findings are advisory only. */
    record Outcome(String overallOutcome, String reasonCode, Map<String, Object> payload) {
    }

    String engine();

    Outcome run(TransactionContext ctx, long requestId, LocalDate assessmentDate, String mode);

    /** What this engine looks up with, recorded on the rule check request. */
    default Map<String, Object> requestPayload(TransactionContext ctx, LocalDate assessmentDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engine", engine());
        payload.put("propertyRef", ctx.propertyRef());
        payload.put("district", ctx.property().get("district_code"));
        payload.put("taluk", ctx.property().get("taluk_code"));
        payload.put("village", ctx.property().get("village_code"));
        payload.put("surveyNo", ctx.property().get("survey_no"));
        payload.put("subdivisionNo", ctx.property().get("subdivision_no"));
        return payload;
    }
}
