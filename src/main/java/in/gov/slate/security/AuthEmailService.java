package in.gov.slate.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import in.gov.slate.common.ApiException;

@Service
public class AuthEmailService {

    private static final Logger log = LoggerFactory.getLogger(AuthEmailService.class);

    private final JavaMailSender mailSender;
    private final GraphMailSender graph;
    private final boolean enabled;
    private final boolean exposeDemoSecrets;
    private final String provider;
    private final String from;
    private final String resetBaseUrl;

    public AuthEmailService(ObjectProvider<JavaMailSender> mailSender, GraphMailSender graph,
                            @Value("${slate.mail.enabled}") boolean enabled,
                            @Value("${slate.mail.expose-demo-secrets}") boolean exposeDemoSecrets,
                            @Value("${slate.mail.provider:GRAPH}") String provider,
                            @Value("${slate.mail.from}") String from,
                            @Value("${slate.mail.reset-base-url}") String resetBaseUrl) {
        this.mailSender = mailSender.getIfAvailable();
        this.graph = graph;
        this.enabled = enabled;
        this.exposeDemoSecrets = exposeDemoSecrets;
        this.provider = provider == null ? "GRAPH" : provider.trim().toUpperCase(java.util.Locale.ROOT);
        this.from = from;
        this.resetBaseUrl = resetBaseUrl;
    }

    /** {@code live}: the user's state has LOGIN_EMAIL_OTP on, so the code is really emailed. */
    public void sendLoginOtp(String email, String fullName, String otp, int validitySeconds, boolean live) {
        String body = "Hello " + fullName + ",\n\nYour SLATE login verification code is " + otp
                + ". It expires in " + (validitySeconds / 60) + " minutes.\n\n"
                + "If you did not attempt to sign in, ignore this email.";
        send(email, "SLATE login verification code", body, "login OTP", live);
    }

    public String sendPasswordReset(String email, String fullName, String token, int validityMinutes,
                                    boolean live) {
        String resetUrl = UriComponentsBuilder.fromUriString(resetBaseUrl)
                .queryParam("token", token)
                .build()
                .encode()
                .toUriString();
        String body = "Hello " + fullName + ",\n\nUse this link to set a new SLATE password:\n"
                + resetUrl + "\n\nThe link expires in " + validityMinutes
                + " minutes and can be used once. Your existing password has not been emailed.";
        send(email, "Reset your SLATE password", body, "password reset link", live);
        return resetUrl;
    }

    public boolean exposesDemoSecrets(boolean live) {
        return exposeDemoSecrets && !enabled && !live;
    }

    private void send(String recipient, String subject, String body, String purpose, boolean live) {
        if (!enabled && !live) {
            if (!exposeDemoSecrets) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                        "Email delivery is not configured");
            }
            log.info("SLATE demo mail recipient={} purpose={}", recipient, purpose);
            return;
        }
        if ("SMTP".equals(provider)) {
            sendSmtp(recipient, subject, body);
        } else {
            sendGraph(recipient, subject, body);
        }
        log.info("SLATE mail sent via {} purpose={}", provider, purpose);
    }

    private void sendGraph(String recipient, String subject, String body) {
        if (!graph.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                    "Email delivery is not configured (set SLATE_GRAPH_TENANT_ID, SLATE_GRAPH_CLIENT_ID, "
                            + "SLATE_GRAPH_CLIENT_SECRET and SLATE_MAIL_FROM)");
        }
        try {
            graph.send(from, recipient, subject, body);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Graph mail delivery failed: {}", ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_FAILED",
                    "Unable to send authentication email");
        }
    }

    private void sendSmtp(String recipient, String subject, String body) {
        if (mailSender == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                    "Email delivery is not configured");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(recipient);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (MailException ex) {
            // e.g. Gmail "535-5.7.8 Username and Password not accepted" when a normal password is used, not an App Password.
            Throwable root = ex.getMostSpecificCause();
            log.warn("SMTP mail delivery from {} failed: {}", from, root.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_FAILED",
                    "Unable to send authentication email");
        }
    }
}
