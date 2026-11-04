package in.gov.slate.consent;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/aadhaar/ekyc")
public class AadhaarEkycController {

    private final AadhaarEkycService ekyc;

    public AadhaarEkycController(AadhaarEkycService ekyc) {
        this.ekyc = ekyc;
    }

    public record OtpBody(@NotBlank String aadhaarNumber) {
    }

    public record VerifyBody(@NotBlank String ekycRef, @NotBlank String otp) {
    }

    @PostMapping("/otp")
    public Map<String, Object> otp(@Valid @RequestBody OtpBody body) {
        return ekyc.requestOtp(body.aadhaarNumber());
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody VerifyBody body) {
        return ekyc.verify(body.ekycRef(), body.otp());
    }
}
