package in.gov.slate.connectors;

public interface AadhaarConnector {

    record OtpRequestResult(String requestReference, int validitySeconds, String maskedTarget, String demoOtp) {
    }

    /** The Aadhaar number itself never leaves the request scope; only its last four digits are echoed. */
    OtpRequestResult requestOtp(String aadhaarNumber, String consentTextVersion);

    boolean verifyOtp(String requestReference, String otp);
}
