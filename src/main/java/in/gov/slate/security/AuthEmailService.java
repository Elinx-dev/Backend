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
    private final boolean enabled;
    private final boolean exposeDemoSecrets;
    private final String from;
    private final String resetBaseUrl;

    public AuthEmailService(ObjectProvider<JavaMailSender> mailSender,
                            @Value("${slate.mail.enabled}") boolean enabled,
                            @Value("${slate.mail.expose-demo-secrets}") boolean exposeDemoSecrets,
                            @Value("${slate.mail.from}") String from,
                            @Value("${slate.mail.reset-base-url}") String resetBaseUrl) {
        this.mailSender = mailSender.getIfAvailable();
        this.enabled = enabled;
        this.exposeDemoSecrets = exposeDemoSecrets;
        this.from = from;
        this.resetBaseUrl = resetBaseUrl;
    }

    public void sendLoginOtp(String email, String fullName, String otp, int validitySeconds) {
        String body = "Hello " + fullName + ",\n\nYour SLATE login verification code is " + otp
                + ". It expires in " + (validitySeconds / 60) + " minutes.\n\n"
                + "If you did not attempt to sign in, ignore this email.";
        send(email, "SLATE login verification code", body, "login OTP");
    }

    public String sendPasswordReset(String email, String fullName, String token, int validityMinutes) {
        String resetUrl = UriComponentsBuilder.fromUriString(resetBaseUrl)
                .queryParam("token", token)
                .build()
                .encode()
                .toUriString();
        String body = "Hello " + fullName + ",\n\nUse this link to set a new SLATE password:\n"
                + resetUrl + "\n\nThe link expires in " + validityMinutes
                + " minutes and can be used once. Your existing password has not been emailed.";
        send(email, "Reset your SLATE password", body, "password reset link");
        return resetUrl;
    }

    public boolean exposesDemoSecrets() {
        return exposeDemoSecrets && !enabled;
    }

    private void send(String recipient, String subject, String body, String purpose) {
        if (!enabled) {
            if (!exposeDemoSecrets) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                        "Email delivery is not configured");
            }
            log.info("SLATE demo mail recipient={} purpose={} body={}", recipient, purpose, body);
            return;
        }
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
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_FAILED",
                    "Unable to send authentication email");
        }
    }
}
