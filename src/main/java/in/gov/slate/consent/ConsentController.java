package in.gov.slate.consent;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/transactions/{txnRef}/consent")
public class ConsentController {

    private final ConsentService consent;

    public ConsentController(ConsentService consent) {
        this.consent = consent;
    }

    public record RequestBody_(List<Long> partyIds) {
    }

    public record VerifyBody(long partyId, @NotBlank String otp) {
    }

    @PostMapping("/request")
    public List<Map<String, Object>> request(@PathVariable String txnRef, @RequestBody(required = false) RequestBody_ body) {
        return consent.requestOtp(txnRef, body == null ? null : body.partyIds());
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@PathVariable String txnRef, @Valid @RequestBody VerifyBody body) {
        return consent.verify(txnRef, body.partyId(), body.otp());
    }

    @GetMapping
    public List<Map<String, Object>> status(@PathVariable String txnRef) {
        return consent.status(txnRef);
    }
}
