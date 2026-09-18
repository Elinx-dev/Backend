package in.gov.slate.security;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.Hashes;

/**
 * Password-based login. The issued token is bound to a server-side session row
 * so it can be revoked.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
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
