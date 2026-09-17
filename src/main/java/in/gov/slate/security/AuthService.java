package in.gov.slate.security;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.Hashes;

/**
 * Two-step officer login: password, then OTP. Both steps are audited, and the
 * issued token is bound to a server-side session row so it can be revoked.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final String demoOtp;
    private final int otpValiditySeconds;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, AuditService audit,
                       @Value("${slate.otp.officer-demo-otp}") String demoOtp,
                       @Value("${slate.otp.validity-seconds}") int otpValiditySeconds) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.demoOtp = demoOtp;
        this.otpValiditySeconds = otpValiditySeconds;
    }

    public record LoginRequest(String username, String password) {
    }

    public record VerifyOtpRequest(String challengeId, String otp) {
    }

    @Transactional
    public Map<String, Object> login(String username, String password, String ip, String userAgent) {
        var user = users.findByUsername(username)
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "INVALID_CREDENTIALS", "Invalid username or password"));
        if (!"ACTIVE".equals(user.status())) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "ACCOUNT_" + user.status(),
                    "Account is " + user.status().toLowerCase() + "; contact the state administrator");
        }
        if (!encoder.matches(password, user.passwordHash())) {
            users.recordLoginFailure(user.id());
            audit.recordAs(user.stateCode(), "LOGIN", "USER", String.valueOf(user.id()), null, null, null,
                    Map.of("username", user.username()), "FAILURE", "Password mismatch");
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS", "Invalid username or password");
        }

        if (!user.mfaRequired()) {
            return issueToken(user, ip, userAgent);
        }

        UUID challengeId = users.createOtpChallenge(user.id(), encoder.encode(demoOtp), otpValiditySeconds);
        audit.recordAs(user.stateCode(), "LOGIN_OTP_REQUESTED", "USER", String.valueOf(user.id()), null, null, null,
                Map.of("challengeId", challengeId.toString(), "username", user.username()), "SUCCESS", null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mfaRequired", true);
        out.put("challengeId", challengeId.toString());
        out.put("otpValiditySeconds", otpValiditySeconds);
        out.put("deliveredTo", maskMobile(user.mobile()));
        // Local/demo builds surface the OTP so the runbook does not need an SMS gateway.
        out.put("demoOtp", demoOtp);
        return out;
    }

    @Transactional
    public Map<String, Object> verifyOtp(String challengeIdRaw, String otp, String ip, String userAgent) {
        UUID challengeId;
        try {
            challengeId = UUID.fromString(challengeIdRaw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("challengeId is not a valid identifier");
        }
        var challenge = users.findOpenChallenge(challengeId)
                .orElseThrow(() -> ApiException.notFound("Login challenge"));
        if (challenge.get("consumed_at") != null) {
            throw ApiException.conflict("This login challenge has already been used");
        }
        long challengePk = ((Number) challenge.get("id")).longValue();
        if (((Number) challenge.get("attempt_count")).intValue() >= 5) {
            throw ApiException.forbidden("Too many OTP attempts for this challenge");
        }
        OffsetDateTime expiresAt = ((java.sql.Timestamp) challenge.get("expires_at")).toInstant()
                .atOffset(ZoneOffset.UTC);
        if (expiresAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw ApiException.badRequest("OTP has expired; start the login again");
        }
        if (!encoder.matches(otp, (String) challenge.get("otp_hash"))) {
            users.incrementChallengeAttempt(challengePk);
            var attempted = users.findById(((Number) challenge.get("user_id")).longValue());
            audit.recordAs(attempted.map(UserRepository.UserRow::stateCode).orElse(null), "LOGIN_OTP_VERIFY", "USER",
                    challenge.get("user_id").toString(), null, null, null, null, "FAILURE", "OTP mismatch");
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_OTP", "Incorrect OTP");
        }
        users.consumeChallenge(challengePk);
        var user = users.findById(((Number) challenge.get("user_id")).longValue())
                .orElseThrow(() -> ApiException.notFound("User"));
        return issueToken(user, ip, userAgent);
    }

    private Map<String, Object> issueToken(UserRepository.UserRow user, String ip, String userAgent) {
        var issued = jwt.issue(user.id(), user.username(), user.stateCode());
        users.openSession(user.id(), issued.jti(), issued.expiresAt().atOffset(ZoneOffset.UTC), ip, userAgent);
        users.recordLoginSuccess(user.id());
        audit.recordAs(user.stateCode(), "LOGIN", "USER", String.valueOf(user.id()), null, null, null,
                Map.of("username", user.username()), "SUCCESS", null);

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

    public void logout(String jti) {
        users.revokeSession(UUID.fromString(jti));
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

    /** Exposed so the consent flow can reuse the same hashing discipline. */
    public static byte[] hashSecret(String value, String salt) {
        return Hashes.aadhaarHash(value, salt);
    }
}
