package in.gov.slate.connectors.mock;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import in.gov.slate.connectors.AadhaarConnector;

/**
 * Local stand-in for the Aadhaar OTP service. It holds only a request reference
 * and the masked last four digits: the Aadhaar number is not retained. Used while the
 * state's AADHAAR_OTP_LIVE flag is off. Each request gets a new random 6-digit OTP,
 * returned as the demo OTP so the screen can show it.
 */
@Component
public class MockAadhaarConnector implements AadhaarConnector {

    private final Map<String, String> issued = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final int validitySeconds;

    public MockAadhaarConnector(@Value("${slate.otp.validity-seconds}") int validitySeconds) {
        this.validitySeconds = validitySeconds;
    }

    @Override
    public OtpRequestResult requestOtp(String aadhaarNumber, String consentTextVersion) {
        String reference = "AADHAAR-OTP-" + UUID.randomUUID();
        String otp = String.format("%06d", random.nextInt(1_000_000));
        issued.put(reference, otp);
        String masked = aadhaarNumber == null || aadhaarNumber.length() < 4
                ? "XXXX"
                : "XXXX-XXXX-" + aadhaarNumber.substring(aadhaarNumber.length() - 4);
        return new OtpRequestResult(reference, validitySeconds, masked, otp);
    }

    @Override
    public boolean verifyOtp(String requestReference, String otp) {
        String expected = requestReference == null ? null : issued.get(requestReference);
        return expected != null && expected.equals(otp);
    }

    @Override
    public String mode() {
        return "MOCK";
    }
}
