package in.gov.slate.fees;

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
@RequestMapping("/api/transactions/{txnRef}")
public class FeeController {

    private final FeeService fees;
    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public FeeController(FeeService fees, PaymentService payments, IdempotencyService idempotency) {
        this.fees = fees;
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @GetMapping("/guideline-value")
    public Map<String, Object> guidelineValue(@PathVariable String txnRef) {
        return fees.guidelineValue(txnRef);
    }

    @PostMapping("/fees")
    public Map<String, Object> calculate(@PathVariable String txnRef) {
        return fees.calculate(txnRef);
    }

    @GetMapping("/payments")
    public List<Map<String, Object>> payments(@PathVariable String txnRef) {
        return payments.list(txnRef);
    }

    @PostMapping("/payments")
    public Map<String, Object> pay(@PathVariable String txnRef,
                                   @Valid @RequestBody PaymentService.PaymentInput body,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var replay = idempotency.replay("payment." + txnRef, key, body);
        if (replay.isPresent()) {
            return payments.summary(txnRef);
        }
        Map<String, Object> result = payments.record(txnRef, body);
        idempotency.store("payment." + txnRef, key, body, Map.of("balance", String.valueOf(result.get("balance"))));
        return result;
    }
}
