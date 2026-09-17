package in.gov.slate.connectors.mock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import in.gov.slate.connectors.AadhaarConnector;

/**
 * Local stand-in for the Aadhaar OTP service. It holds only a request reference
 * and the masked last four digits: the Aadhaar number is not retained.
 */
@Component
@ConditionalOnProperty(name = "slate.connectors.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockAadhaarConnector implements AadhaarConnector {

    private final Map<String, String> issued = new ConcurrentHashMap<>();
    private final String demoOtp;
    private final int validitySeconds;

    public MockAadhaarConnector(@Value("${slate.otp.aadhaar-demo-otp}") String demoOtp,
                                @Value("${slate.otp.validity-seconds}") int validitySeconds) {
        this.demoOtp = demoOtp;
        this.validitySeconds = validitySeconds;
    }

    @Override
    public OtpRequestResult requestOtp(String aadhaarNumber, String consentTextVersion) {
        String reference = "AADHAAR-OTP-" + UUID.randomUUID();
        issued.put(reference, demoOtp);
        String masked = aadhaarNumber == null || aadhaarNumber.length() < 4
                ? "XXXX"
                : "XXXX-XXXX-" + aadhaarNumber.substring(aadhaarNumber.length() - 4);
        return new OtpRequestResult(reference, validitySeconds, masked, demoOtp);
    }

    @Override
    public boolean verifyOtp(String requestReference, String otp) {
        String expected = issued.get(requestReference);
        return expected != null && expected.equals(otp);
    }
}
