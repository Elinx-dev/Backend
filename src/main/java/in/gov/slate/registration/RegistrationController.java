package in.gov.slate.registration;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.IdempotencyService;

@RestController
@RequestMapping("/api/transactions/{txnRef}/registration")
public class RegistrationController {

    private final RegistrationService registration;
    private final IdempotencyService idempotency;

    public RegistrationController(RegistrationService registration, IdempotencyService idempotency) {
        this.registration = registration;
        this.idempotency = idempotency;
    }

    @PostMapping
    public Map<String, Object> register(@PathVariable String txnRef,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var replay = idempotency.replay("register." + txnRef, key, Map.of("txnRef", txnRef));
        if (replay.isPresent()) {
            return registration.result(txnRef);
        }
        Map<String, Object> result = registration.register(txnRef);
        idempotency.store("register." + txnRef, key, Map.of("txnRef", txnRef),
                Map.of("documentNo", String.valueOf(result.get("registeredDocumentNo"))));
        return result;
    }

    @GetMapping
    public Map<String, Object> result(@PathVariable String txnRef) {
        return registration.result(txnRef);
    }
}
