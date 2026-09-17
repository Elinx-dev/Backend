package in.gov.slate.survey;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.IdempotencyService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transactions/{txnRef}/survey")
public class SurveyController {

    private final SurveyService survey;
    private final IdempotencyService idempotency;

    public SurveyController(SurveyService survey, IdempotencyService idempotency) {
        this.survey = survey;
        this.idempotency = idempotency;
    }

    @GetMapping("/visits")
    public List<Map<String, Object>> visits(@PathVariable String txnRef) {
        return survey.visits(txnRef);
    }

    @PostMapping("/visits")
    public Map<String, Object> propose(@PathVariable String txnRef,
                                       @Valid @RequestBody SurveyService.VisitRequest body) {
        return survey.proposeVisit(txnRef, body);
    }

    @PostMapping("/visits/{visitId}/accept")
    public Map<String, Object> accept(@PathVariable String txnRef, @PathVariable long visitId) {
        return survey.acceptVisit(txnRef, visitId);
    }

    @PostMapping("/visits/{visitId}/check-in")
    public Map<String, Object> checkIn(@PathVariable String txnRef, @PathVariable long visitId) {
        return survey.checkIn(txnRef, visitId);
    }

    @GetMapping("/submissions")
    public List<Map<String, Object>> submissions(@PathVariable String txnRef) {
        return survey.submissions(txnRef);
    }

    @PostMapping("/submissions")
    public Map<String, Object> submit(@PathVariable String txnRef,
                                      @Valid @RequestBody SurveyService.SubmissionRequest body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var replay = idempotency.replay("survey." + txnRef, key, body);
        if (replay.isPresent()) {
            return Map.of("txnRef", txnRef, "submissions", survey.submissions(txnRef));
        }
        Map<String, Object> result = survey.submit(txnRef, body);
        idempotency.store("survey." + txnRef, key, body,
                Map.of("submissionId", String.valueOf(result.get("submissionId"))));
        return result;
    }
}
