package in.gov.slate.connectors;

public interface AadhaarConnector {

    record OtpRequestResult(String requestReference, int validitySeconds, String maskedTarget, String demoOtp) {
    }

    /**
     * The Aadhaar number itself never leaves the request scope; only its last four digits are echoed.
     * The live connector needs all 12 digits, the mock only the last four.
     */
    OtpRequestResult requestOtp(String aadhaarNumber, String consentTextVersion);

    boolean verifyOtp(String requestReference, String otp);

    /** MOCK (demo OTP) or LIVE (real Aadhaar OTP API); a state admin feature flag picks which one is used. */
    String mode();
}
