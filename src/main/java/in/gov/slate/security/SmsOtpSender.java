package in.gov.slate.security;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.ApiException;

/**
 * Sends the login verification code by SMS through 2Factor.in (custom-OTP API:
 * {@code GET {base}/API/V1/{apiKey}/SMS/{mobile}/{otp}/{template}}). The sender ID is the one
 * approved for the DLT template in the 2Factor account. The API key comes only from SLATE_SMS_2FACTOR_API_KEY.
 */
@Component
public class SmsOtpSender {

    private static final Logger log = LoggerFactory.getLogger(SmsOtpSender.class);

    private final String apiKey;
    private final String baseUrl;
    private final String template;
    private final String senderId;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SmsOtpSender(@Value("${slate.sms.two-factor.api-key:}") String apiKey,
                        @Value("${slate.sms.two-factor.base-url:https://2factor.in}") String baseUrl,
                        @Value("${slate.sms.two-factor.template:}") String template,
                        @Value("${slate.sms.two-factor.sender-id:}") String senderId,
                        ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.template = template;
        this.senderId = senderId;
        this.mapper = mapper;
    }

    /** Ten-digit Indian mobile number, or null when the stored value isn't one. */
    public static String indianMobile(String mobile) {
        if (mobile == null) {
            return null;
        }
        String digits = mobile.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        return digits.matches("[6-9]\\d{9}") ? digits : null;
    }

    public void sendLoginOtp(String mobile, String otp) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_DELIVERY_UNAVAILABLE",
                    "SMS delivery is not configured (set SLATE_SMS_2FACTOR_API_KEY)");
        }
        String url = baseUrl + "/API/V1/" + enc(apiKey) + "/SMS/" + enc(mobile) + "/" + enc(otp)
                + (template == null || template.isBlank() ? "" : "/" + enc(template));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = mapper.readTree(response.body());
            if (response.statusCode() / 100 == 2 && "Success".equalsIgnoreCase(body.path("Status").asText())) {
                log.info("2Factor login OTP sent to mobile ending {} (sender {})", mobile.substring(6), senderId);
                return;
            }
            log.warn("2Factor SMS to mobile ending {} failed: HTTP {} {}", mobile.substring(6),
                    response.statusCode(), body.path("Details").asText());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("2Factor SMS call failed: {}", ex.getClass().getSimpleName());
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_DELIVERY_FAILED",
                "Unable to send the verification SMS");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
