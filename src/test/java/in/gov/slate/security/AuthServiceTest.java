package in.gov.slate.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder encoder;
    @Mock
    private PasswordCryptoService passwordCrypto;
    @Mock
    private AuthEmailService email;
    @Mock
    private JwtService jwt;
    @Mock
    private AuditService audit;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, encoder, passwordCrypto, email, jwt, audit, "123456", 300, 15);
    }

    @Test
    void validPasswordStartsEmailMfaWithoutIssuingSession() {
        var user = user(true);
        when(users.findByLoginId("ro.adyar")).thenReturn(Optional.of(user));
        when(passwordCrypto.decrypt("encrypted")).thenReturn("Slate@123");
        when(encoder.matches("Slate@123", user.passwordHash())).thenReturn(true);
        when(email.exposesDemoSecrets()).thenReturn(true);
        when(encoder.encode("123456")).thenReturn("otp-hash");

        var result = service.login("ro.adyar", "encrypted", "127.0.0.1", "test");

        assertThat(result).containsEntry("mfaRequired", true)
                .containsEntry("maskedEmail", "r***@tn.demo.slate")
                .containsEntry("demoOtp", "123456");
        verify(users).createLoginOtp(eq(user.id()), any(UUID.class), eq("otp-hash"), any(OffsetDateTime.class));
        verify(email).sendLoginOtp(user.email(), user.fullName(), "123456", 300);
    }

    @Test
    void validMfaCodeIssuesSessionAndConsumesChallenge() {
        UUID challengeId = UUID.randomUUID();
        UUID jti = UUID.randomUUID();
        var user = user(true);
        var challenge = new UserRepository.LoginOtpRow(
                user.id(), "otp-hash", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5), null, 0);
        when(users.findLoginOtpForUpdate(challengeId)).thenReturn(Optional.of(challenge));
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(encoder.matches("123456", "otp-hash")).thenReturn(true);
        when(jwt.issue(user.id(), user.username(), user.stateCode()))
                .thenReturn(new JwtService.Issued("access-token", jti, Instant.now().plusSeconds(600)));
        when(users.toCurrentUser(user)).thenReturn(currentUser(user));
        when(users.jurisdictions(user.id())).thenReturn(List.of());

        var result = service.verifyMfa(challengeId.toString(), "123456", "127.0.0.1", "test");

        assertThat(result).containsEntry("mfaRequired", false).containsEntry("accessToken", "access-token");
        verify(users).consumeLoginOtp(challengeId);
        verify(users).openSession(eq(user.id()), eq(jti), any(OffsetDateTime.class), eq("127.0.0.1"), eq("test"));
    }

    @Test
    void passwordResetUpdatesHashAndRevokesExistingSessions() {
        var user = user(true);
        var reset = new UserRepository.PasswordResetRow(
                99L, user.id(), OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5), null);
        when(users.findPasswordResetForUpdate(any(byte[].class))).thenReturn(Optional.of(reset));
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwordCrypto.decrypt("encrypted-new")).thenReturn("New@1234");
        when(encoder.matches("New@1234", user.passwordHash())).thenReturn(false);
        when(encoder.encode("New@1234")).thenReturn("new-hash");

        service.resetPassword("reset-token", "encrypted-new");

        verify(users).updatePassword(user.id(), "new-hash");
        verify(users).consumePasswordReset(reset.id());
        verify(users).revokeAllSessions(user.id());
    }

    private UserRepository.UserRow user(boolean mfaRequired) {
        return new UserRepository.UserRow(
                1L, "ro.adyar", "R. Anandhi", "ro.adyar@tn.demo.slate", "9000000001",
                "Sub-Registrar", "REGISTRATION", "TN", "old-hash", mfaRequired, "ACTIVE", 0);
    }

    private CurrentUser currentUser(UserRepository.UserRow user) {
        return new CurrentUser(
                user.id(), user.username(), user.fullName(), user.stateCode(), user.department(),
                Set.of("REGISTRATION_OFFICER"), Set.of("TXN_EDIT"), Set.of("ADYAR"), Set.of());
    }
}
