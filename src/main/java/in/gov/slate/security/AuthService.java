package in.gov.slate.security;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.Hashes;

@Service
public class AuthService {

    private static final int MAX_OTP_ATTEMPTS = 5;

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordCryptoService passwordCrypto;
    private final AuthEmailService email;
    private final JwtService jwt;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final String demoOtp;
    private final int otpValiditySeconds;
    private final int resetValidityMinutes;

    public AuthService(UserRepository users, PasswordEncoder encoder, PasswordCryptoService passwordCrypto,
                       AuthEmailService email, JwtService jwt, AuditService audit,
                       @Value("${slate.otp.officer-demo-otp}") String demoOtp,
                       @Value("${slate.otp.validity-seconds}") int otpValiditySeconds,
                       @Value("${slate.password-reset.validity-minutes}") int resetValidityMinutes) {
        this.users = users;
        this.encoder = encoder;
        this.passwordCrypto = passwordCrypto;
        this.email = email;
        this.jwt = jwt;
        this.audit = audit;
        this.demoOtp = demoOtp;
        this.otpValiditySeconds = otpValiditySeconds;
        this.resetValidityMinutes = resetValidityMinutes;
    }

    @Transactional
    public Map<String, Object> login(String loginId, String encryptedPassword, String ip, String userAgent) {
        var user = users.findByLoginId(loginId)
                .orElseThrow(this::invalidCredentials);
        requireActive(user);

        String password = passwordCrypto.decrypt(encryptedPassword);
        if (!encoder.matches(password, user.passwordHash())) {
            users.recordLoginFailure(user.id());
            audit.recordAs(user.stateCode(), "LOGIN", "USER", String.valueOf(user.id()), null, null, null,
                    Map.of("username", user.username()), "FAILURE", "Password mismatch");
            throw invalidCredentials();
        }

        if (!user.mfaRequired()) {
            return issueToken(user, ip, userAgent, true);
        }
        if (user.email() == null || user.email().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "MFA_EMAIL_MISSING",
                    "No email address is configured for this account");
        }

        String otp = email.exposesDemoSecrets() ? demoOtp : String.format("%06d", random.nextInt(1_000_000));
        UUID challengeId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(otpValiditySeconds);
        users.createLoginOtp(user.id(), challengeId, encoder.encode(otp), expiresAt);
        audit.recordAs(user.stateCode(), "LOGIN_MFA_REQUESTED", "USER", String.valueOf(user.id()), null, null,
                null, Map.of("username", user.username()), "SUCCESS", null);
        email.sendLoginOtp(user.email(), user.fullName(), otp, otpValiditySeconds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mfaRequired", true);
        out.put("challengeId", challengeId.toString());
        out.put("maskedEmail", maskEmail(user.email()));
        out.put("expiresAt", expiresAt.toString());
        if (email.exposesDemoSecrets()) {
            out.put("demoOtp", otp);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> verifyMfa(String challengeIdValue, String otp, String ip, String userAgent) {
        UUID challengeId = parseUuid(challengeIdValue, "Invalid MFA challenge");
        var challenge = users.findLoginOtpForUpdate(challengeId)
                .orElseThrow(this::invalidMfa);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (challenge.consumedAt() != null || !challenge.expiresAt().isAfter(now)
                || challenge.attemptCount() >= MAX_OTP_ATTEMPTS) {
            throw invalidMfa();
        }
        var user = users.findById(challenge.userId()).orElseThrow(this::invalidMfa);
        requireActive(user);
        if (!encoder.matches(otp, challenge.otpHash())) {
            users.recordLoginOtpFailure(challengeId);
            audit.recordAs(user.stateCode(), "LOGIN_MFA_VERIFY", "USER", String.valueOf(user.id()), null, null,
                    null, Map.of("username", user.username()), "FAILURE", "OTP mismatch");
            throw invalidMfa();
        }

        users.consumeLoginOtp(challengeId);
        return issueToken(user, ip, userAgent, true);
    }

    @Transactional
    public Map<String, Object> refresh(CurrentUser current, String currentJti, String ip, String userAgent) {
        UUID jti = parseUuid(currentJti, "Invalid session");
        var user = users.findById(current.id())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                        "Your session has expired"));
        requireActive(user);
        users.revokeSession(jti);
        return issueToken(user, ip, userAgent, false);
    }

    @Transactional
    public Map<String, Object> requestPasswordReset(String loginId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "If the account exists, a password reset link has been sent to its registered email.");

        var possibleUser = users.findByLoginId(loginId);
        if (possibleUser.isEmpty()) {
            return out;
        }
        var user = possibleUser.get();
        if ("DISABLED".equals(user.status()) || user.email() == null || user.email().isBlank()) {
            return out;
        }

        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(resetValidityMinutes);
        users.createPasswordResetToken(user.id(), Hashes.sha256(token), expiresAt);
        audit.recordAs(user.stateCode(), "PASSWORD_RESET_REQUESTED", "USER", String.valueOf(user.id()), null, null,
                null, Map.of("username", user.username()), "SUCCESS", null);
        String resetUrl = email.sendPasswordReset(user.email(), user.fullName(), token, resetValidityMinutes);
        if (email.exposesDemoSecrets()) {
            out.put("demoResetUrl", resetUrl);
        }
        return out;
    }

    @Transactional
    public void resetPassword(String token, String encryptedPassword) {
        var reset = users.findPasswordResetForUpdate(Hashes.sha256(token))
                .orElseThrow(this::invalidResetToken);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (reset.consumedAt() != null || !reset.expiresAt().isAfter(now)) {
            throw invalidResetToken();
        }
        var user = users.findById(reset.userId()).orElseThrow(this::invalidResetToken);
        String newPassword = passwordCrypto.decrypt(encryptedPassword);
        validateNewPassword(newPassword);
        if (encoder.matches(newPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_REUSED",
                    "New password must be different from the current password");
        }

        users.updatePassword(user.id(), encoder.encode(newPassword));
        users.consumePasswordReset(reset.id());
        users.revokeAllSessions(user.id());
        audit.recordAs(user.stateCode(), "PASSWORD_RESET", "USER", String.valueOf(user.id()), null, null,
                null, Map.of("username", user.username()), "SUCCESS", null);
    }

    private Map<String, Object> issueToken(UserRepository.UserRow user, String ip, String userAgent,
                                           boolean recordLogin) {
        var issued = jwt.issue(user.id(), user.username(), user.stateCode());
        users.openSession(user.id(), issued.jti(), issued.expiresAt().atOffset(ZoneOffset.UTC), ip, userAgent);
        if (recordLogin) {
            users.recordLoginSuccess(user.id());
            audit.recordAs(user.stateCode(), "LOGIN", "USER", String.valueOf(user.id()), null, null, null,
                    Map.of("username", user.username()), "SUCCESS", null);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mfaRequired", false);
        out.put("accessToken", issued.token());
        out.put("tokenType", "Bearer");
        out.put("expiresAt", issued.expiresAt().toString());
        out.put("user", profile(user));
        return out;
    }

    public Map<String, Object> profile(UserRepository.UserRow user) {
        CurrentUser cu = users.toCurrentUser(user);
        List<Map<String, Object>> jurisdictions = users.jurisdictions(user.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.id());
        out.put("username", user.username());
        out.put("fullName", user.fullName());
        out.put("email", user.email());
        out.put("mobile", maskMobile(user.mobile()));
        out.put("designation", user.designation());
        out.put("department", user.department());
        out.put("stateCode", user.stateCode());
        out.put("roles", cu.roles());
        out.put("permissions", cu.permissions());
        out.put("jurisdictions", jurisdictions);
        out.put("homeRoute", homeRoute(cu.roles()));
        return out;
    }

    public String publicKey() {
        return passwordCrypto.publicKey();
    }

    public void logout(String jti) {
        users.revokeSession(parseUuid(jti, "Invalid session"));
    }

    private void validateNewPassword(String password) {
        boolean valid = password.length() >= 8 && password.length() <= 128
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
        if (!valid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD",
                    "Password must be 8-128 characters and include uppercase, lowercase, number and symbol");
        }
    }

    private void requireActive(UserRepository.UserRow user) {
        if (!"ACTIVE".equals(user.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_" + user.status(),
                    "Account is " + user.status().toLowerCase() + "; contact the state administrator");
        }
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
    }

    private ApiException invalidMfa() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA", "Invalid or expired verification code");
    }

    private ApiException invalidResetToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN",
                "Password reset link is invalid or has expired");
    }

    private UUID parseUuid(String value, String message) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
        }
    }

    private String homeRoute(java.util.Set<String> roles) {
        if (roles.contains("REGISTRATION_OFFICER")) {
            return "/ro";
        }
        if (roles.contains("SURVEYOR")) {
            return "/surveyor";
        }
        if (roles.contains("VAO")) {
            return "/vao";
        }
        if (roles.contains("TAHSILDAR")) {
            return "/tahsildar";
        }
        if (roles.contains("STATE_ADMIN")) {
            return "/admin";
        }
        return "/public";
    }

    private String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return null;
        }
        return "XXXXXX" + mobile.substring(mobile.length() - 4);
    }

    private String maskEmail(String emailAddress) {
        int separator = emailAddress.indexOf('@');
        if (separator <= 0) {
            return "***";
        }
        String local = emailAddress.substring(0, separator);
        String maskedLocal = local.length() == 1 ? "*" : local.charAt(0) + "***";
        return maskedLocal + emailAddress.substring(separator);
    }
}
