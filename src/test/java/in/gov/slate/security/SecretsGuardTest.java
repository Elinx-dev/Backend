package in.gov.slate.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretsGuardTest {

    private static final String STRONG = "a-private-jwt-secret-that-is-long-enough-123";
    private static final String PII = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void demoDefaultsFailOutsideLocalDemo() {
        assertThatThrownBy(() -> new SecretsGuard(false, SecretsGuard.DEMO_JWT_SECRET, "", "local-demo-aadhaar-salt",
                true, "MOCK", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SLATE_JWT_SECRET")
                .hasMessageContaining("SLATE_PII_KEY")
                .hasMessageContaining("SLATE_CONNECTORS_MODE");
    }

    @Test
    void demoDefaultsOnlyWarnInLocalDemo() {
        assertThatCode(() -> new SecretsGuard(true, SecretsGuard.DEMO_JWT_SECRET, "", "local-demo-aadhaar-salt",
                true, "MOCK", true)).doesNotThrowAnyException();
    }

    @Test
    void productionSettingsPass() {
        assertThat(SecretsGuard.problems(STRONG, PII, "prod-salt", false, "HTTP", false)).isEmpty();
        assertThatCode(() -> new SecretsGuard(false, STRONG, PII, "prod-salt", false, "HTTP", false))
                .doesNotThrowAnyException();
    }

    @Test
    void shortJwtSecretIsRejected() {
        assertThat(SecretsGuard.problems("short", PII, "prod-salt", false, "HTTP", false))
                .anyMatch(p -> p.contains("SLATE_JWT_SECRET"));
    }
}
