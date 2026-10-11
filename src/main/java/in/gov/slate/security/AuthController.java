package in.gov.slate.security;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final UserRepository users;

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    public record LoginBody(@NotBlank @Size(max = 320) String loginId,
                            @NotBlank @Size(max = 512) String encryptedPassword) {
    }

    public record MfaBody(@NotBlank @Size(max = 36) String challengeId,
                          @NotBlank @Pattern(regexp = "\\d{6}") String otp) {
    }

    public record PasswordResetRequest(@NotBlank @Size(max = 320) String loginId) {
    }

    public record PasswordResetConfirm(@NotBlank @Size(max = 128) String token,
                                       @NotBlank @Size(max = 512) String encryptedPassword) {
    }

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, Object>> publicKey() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("algorithm", "RSA-OAEP-256", "publicKey", auth.publicKey()));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginBody body, HttpServletRequest request) {
        return auth.login(body.loginId().trim(), body.encryptedPassword(), request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }

    @PostMapping("/mfa/verify")
    public Map<String, Object> verifyMfa(@Valid @RequestBody MfaBody body, HttpServletRequest request) {
        return auth.verifyMfa(body.challengeId(), body.otp(), request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }

    @PostMapping("/password-reset/request")
    public Map<String, Object> requestPasswordReset(@Valid @RequestBody PasswordResetRequest body) {
        return auth.requestPasswordReset(body.loginId().trim());
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirm body) {
        auth.resetPassword(body.token(), body.encryptedPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(HttpServletRequest request) {
        CurrentUser current = CurrentUser.require();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getCredentials() == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                    "Your session has expired");
        }
        return auth.refresh(current, authentication.getCredentials().toString(), request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser current = CurrentUser.require();
        var row = users.findByUsername(current.username()).orElseThrow(() -> ApiException.notFound("User"));
        return auth.profile(row);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() != null) {
            auth.logout(authentication.getCredentials().toString());
        }
        return ResponseEntity.noContent().build();
    }
}
