package in.gov.slate.connectors.live;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import in.gov.slate.common.ApiException;
import in.gov.slate.connectors.AadhaarConnector;

/**
 * Real Aadhaar OTP through the state's Aadhaar gateway (ASA / KUA provider), used while the
 * state's AADHAAR_OTP_LIVE flag is on. The gateway URL, paths and API key come from
 * SLATE_AADHAAR_API_*; the Aadhaar number is sent only to the gateway and never stored or logged.
 */
@Component
public class LiveAadhaarConnector implements AadhaarConnector {

    private static final Logger log = LoggerFactory.getLogger(LiveAadhaarConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final String baseUrl;
    private final String apiKey;
    private final String otpPath;
    private final String verifyPath;
    private final int validitySeconds;
    private final RestClient http;

    public LiveAadhaarConnector(@Value("${slate.aadhaar.live.base-url:}") String baseUrl,
                                @Value("${slate.aadhaar.live.api-key:}") String apiKey,
                                @Value("${slate.aadhaar.live.otp-path:/otp/request}") String otpPath,
                                @Value("${slate.aadhaar.live.verify-path:/otp/verify}") String verifyPath,
                                @Value("${slate.aadhaar.live.timeout-seconds:15}") int timeoutSeconds,
                                @Value("${slate.otp.validity-seconds}") int validitySeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey;
        this.otpPath = otpPath;
        this.verifyPath = verifyPath;
        this.validitySeconds = validitySeconds;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        // Buffered so the gateway receives a Content-Length instead of a chunked body.
        this.http = RestClient.builder().requestFactory(new BufferingClientHttpRequestFactory(factory)).build();
    }

    @Override
    public OtpRequestResult requestOtp(String aadhaarNumber, String consentTextVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aadhaarNumber", aadhaarNumber);
        body.put("consent", "Y");
        body.put("consentTextVersion", consentTextVersion);
        Map<String, Object> response = post(otpPath, body);
        String reference = firstText(response, "requestReference", "txnId", "transactionId", "referenceId",
                "reference_id", "request_id");
        if (reference == null) {
            throw unavailable("The Aadhaar OTP service did not return a request reference");
        }
        String masked = "XXXX-XXXX-" + aadhaarNumber.substring(aadhaarNumber.length() - 4);
        return new OtpRequestResult(reference, validitySeconds, masked, null);
    }

    @Override
    public boolean verifyOtp(String requestReference, String otp) {
        Map<String, Object> response = post(verifyPath, Map.of("requestReference", requestReference, "otp", otp));
        if (Boolean.TRUE.equals(response.get("verified")) || Boolean.TRUE.equals(response.get("valid"))) {
            return true;
        }
        String status = firstText(response, "status", "result");
        return status != null && java.util.Set.of("SUCCESS", "VERIFIED", "VALID", "Y")
                .contains(status.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public String mode() {
        return "LIVE";
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        if (baseUrl.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AADHAAR_API_NOT_CONFIGURED",
                    "Live Aadhaar OTP is switched on, but SLATE_AADHAAR_API_URL is not configured");
        }
        try {
            Map<String, Object> response = http.post()
                    .uri(baseUrl + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            h.setBearerAuth(apiKey);
                            h.set("x-api-key", apiKey);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(JSON);
            return response == null ? Map.of() : response;
        } catch (RestClientException ex) {
            log.warn("Aadhaar OTP service call to {} failed: {}", path, ex.getClass().getSimpleName());
            throw unavailable("The Aadhaar OTP service is not reachable; try again or switch it off in Feature flags");
        }
    }

    private static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AADHAAR_API_UNAVAILABLE", message);
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        Object data = map.get("data");
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null && data instanceof Map<?, ?> nested) {
                value = nested.get(key);
            }
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }
}
