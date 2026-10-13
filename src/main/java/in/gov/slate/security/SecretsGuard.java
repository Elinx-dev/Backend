package in.gov.slate.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stops the application from starting outside local demo use while demo secrets, mock
 * connectors or public API docs are still configured. In local demo mode it only warns.
 */
@Component
public class SecretsGuard {

    private static final Logger log = LoggerFactory.getLogger(SecretsGuard.class);
    static final String DEMO_JWT_SECRET = "local-demo-only-secret-key-please-override-in-any-real-environment";
    static final int MIN_JWT_SECRET_BYTES = 32;

    public SecretsGuard(@Value("${slate.security.local-demo:true}") boolean localDemo,
                        @Value("${slate.jwt.secret}") String jwtSecret,
                        @Value("${slate.pii.key:}") String piiKey,
                        @Value("${slate.aadhaar.salt}") String aadhaarSalt,
                        @Value("${slate.mail.expose-demo-secrets}") boolean exposeDemoSecrets,
                        @Value("${slate.connectors.mode:MOCK}") String connectorsMode,
                        @Value("${springdoc.api-docs.enabled:true}") boolean apiDocsEnabled) {
        List<String> problems = problems(jwtSecret, piiKey, aadhaarSalt, exposeDemoSecrets, connectorsMode,
                apiDocsEnabled);
        if (problems.isEmpty()) {
            return;
        }
        if (!localDemo) {
            throw new IllegalStateException("Insecure configuration with SLATE_LOCAL_DEMO=false: "
                    + String.join("; ", problems));
        }
        log.warn("Local demo configuration in use (set SLATE_LOCAL_DEMO=false outside local use): {}",
                String.join("; ", problems));
    }

    static List<String> problems(String jwtSecret, String piiKey, String aadhaarSalt, boolean exposeDemoSecrets,
                                 String connectorsMode, boolean apiDocsEnabled) {
        List<String> problems = new ArrayList<>();
        if (jwtSecret == null || DEMO_JWT_SECRET.equals(jwtSecret)
                || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            problems.add("SLATE_JWT_SECRET must be a private value of at least " + MIN_JWT_SECRET_BYTES + " bytes");
        }
        if (piiKey == null || piiKey.isBlank()) {
            problems.add("SLATE_PII_KEY is not set (the built-in demo key would be used)");
        }
        if (aadhaarSalt == null || aadhaarSalt.isBlank() || aadhaarSalt.startsWith("local-demo")) {
            problems.add("SLATE_AADHAAR_SALT is the demo value");
        }
        if (exposeDemoSecrets) {
            problems.add("SLATE_EXPOSE_DEMO_SECRETS must be false (OTP and reset links would be logged)");
        }
        if ("MOCK".equalsIgnoreCase(connectorsMode)) {
            problems.add("SLATE_CONNECTORS_MODE is MOCK (mock court/tax endpoints are enabled)");
        }
        if (apiDocsEnabled) {
            problems.add("SLATE_API_DOCS_ENABLED should be false (Swagger UI is public)");
        }
        return problems;
    }
}
