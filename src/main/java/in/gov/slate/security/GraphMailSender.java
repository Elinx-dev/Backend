package in.gov.slate.security;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sends mail through Microsoft Graph with an app registration (client-credentials flow), the
 * same mechanism BUMI uses. Credentials come from SLATE_GRAPH_* and are never stored in the repo.
 */
@Component
public class GraphMailSender {

    private static final Logger log = LoggerFactory.getLogger(GraphMailSender.class);

    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String accessToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public GraphMailSender(@Value("${slate.mail.graph.tenant-id:}") String tenantId,
                           @Value("${slate.mail.graph.client-id:}") String clientId,
                           @Value("${slate.mail.graph.client-secret:}") String clientSecret,
                           ObjectMapper mapper) {
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.mapper = mapper;
    }

    public boolean configured() {
        return !blank(tenantId) && !blank(clientId) && !blank(clientSecret);
    }

    /** Sends a plain-text mail from the {@code from} mailbox; throws when Graph refuses it. */
    public void send(String from, String to, String subject, String text) throws Exception {
        Map<String, Object> mail = Map.of(
                "message", Map.of(
                        "subject", subject,
                        "body", Map.of("contentType", "Text", "content", text),
                        "toRecipients", List.of(Map.of("emailAddress", Map.of("address", to)))),
                "saveToSentItems", false);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://graph.microsoft.com/v1.0/users/"
                        + URLEncoder.encode(from, StandardCharsets.UTF_8) + "/sendMail"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mail)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            // e.g. 404 MailboxNotEnabledForRESTAPI: the SLATE_MAIL_FROM mailbox has no Exchange Online licence.
            String reason = graphError(response.body());
            log.warn("Graph sendMail from {} returned HTTP {} {}", from, response.statusCode(), reason);
            throw new IllegalStateException("Graph sendMail failed with HTTP " + response.statusCode() + " " + reason);
        }
    }

    private synchronized String token() throws Exception {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return accessToken;
        }
        String form = "client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
                + "&scope=" + enc("https://graph.microsoft.com/.default") + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://login.microsoftonline.com/" + enc(tenantId) + "/oauth2/v2.0/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = mapper.readTree(response.body());
        if (response.statusCode() / 100 != 2 || !body.hasNonNull("access_token")) {
            log.warn("Graph token request returned HTTP {} {} {}", response.statusCode(), body.path("error").asText(),
                    body.path("error_description").asText().lines().findFirst().orElse(""));
            throw new IllegalStateException("Graph token request failed with HTTP " + response.statusCode());
        }
        accessToken = body.get("access_token").asText();
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, body.path("expires_in").asLong(3600) - 120));
        return accessToken;
    }

    /** Graph's own error code and message, e.g. "ErrorInvalidUser: The requested user ... is invalid." */
    private String graphError(String body) {
        try {
            JsonNode error = mapper.readTree(body).path("error");
            return error.path("code").asText() + ": " + error.path("message").asText();
        } catch (Exception ex) {
            return "";
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
