package in.gov.slate.transaction;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactions;
    private final IdempotencyService idempotency;

    public TransactionController(TransactionService transactions, IdempotencyService idempotency) {
        this.transactions = transactions;
        this.idempotency = idempotency;
    }

    public record TransitionBody(@NotBlank String actionCode, String reason) {
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody TransactionService.CreateRequest body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var replay = idempotency.replay("transaction.create", key, body);
        if (replay.isPresent()) {
            return transactions.detail(replay.get().get("txnRef").toString());
        }
        Map<String, Object> created = transactions.create(body);
        idempotency.store("transaction.create", key, body, Map.of("txnRef", created.get("txn_ref")));
        return created;
    }

    @GetMapping
    public List<Map<String, Object>> queue(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String stage,
                                           @RequestParam(required = false) String sroCode,
                                           @RequestParam(defaultValue = "100") int limit) {
        return transactions.queue(status, stage, sroCode, Math.min(limit, 500));
    }

    @GetMapping({"/{txnRef}", "/ref/{txnRef}"})
    public Map<String, Object> detail(@PathVariable String txnRef) {
        return transactions.detail(txnRef);
    }

    @PutMapping("/{txnRef}/details")
    public Map<String, Object> updateDetails(@PathVariable String txnRef,
                                             @Valid @RequestBody TransactionService.DetailsRequest body) {
        return transactions.updateDetails(txnRef, body);
    }

    @PutMapping("/{txnRef}/parties")
    public Map<String, Object> saveParties(@PathVariable String txnRef,
                                           @Valid @RequestBody List<TransactionService.PartyInput> body) {
        return transactions.saveParties(txnRef, body);
    }

    @PutMapping("/{txnRef}/witnesses")
    public Map<String, Object> saveWitnesses(@PathVariable String txnRef,
                                             @Valid @RequestBody List<TransactionService.WitnessInput> body) {
        return transactions.saveWitnesses(txnRef, body);
    }

    @PostMapping("/{txnRef}/transitions")
    public Map<String, Object> transition(@PathVariable String txnRef, @Valid @RequestBody TransitionBody body,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var replay = idempotency.replay("transaction.transition." + txnRef, key, body);
        if (replay.isPresent()) {
            return transactions.detail(txnRef);
        }
        Map<String, Object> result = transactions.transition(txnRef, body.actionCode(), body.reason());
        idempotency.store("transaction.transition." + txnRef, key, body, Map.of("status", result.get("status")));
        return result;
    }
}
