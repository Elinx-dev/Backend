package in.gov.slate.rules;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions/{txnRef}/rule-checks")
public class RuleCheckController {

    private final RuleCheckService ruleChecks;

    public RuleCheckController(RuleCheckService ruleChecks) {
        this.ruleChecks = ruleChecks;
    }

    public record RunBody(List<String> engines, String mode, LocalDate assessmentDate) {
    }

    @PostMapping
    public List<Map<String, Object>> run(@PathVariable String txnRef,
                                         @RequestBody(required = false) RunBody body) {
        return ruleChecks.run(txnRef,
                body == null ? null : body.engines(),
                body == null ? null : body.mode(),
                body == null ? null : body.assessmentDate());
    }

    @GetMapping
    public List<Map<String, Object>> results(@PathVariable String txnRef) {
        return ruleChecks.results(txnRef);
    }
}
