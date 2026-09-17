package in.gov.slate.security;

import java.util.Map;

import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final UserRepository users;

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    public record LoginBody(@NotBlank String username, @NotBlank String password) {
    }

    public record OtpBody(@NotBlank String challengeId, @NotBlank String otp) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginBody body, HttpServletRequest request) {
        return auth.login(body.username(), body.password(), request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }

    @PostMapping("/verify-otp")
    public Map<String, Object> verifyOtp(@Valid @RequestBody OtpBody body, HttpServletRequest request) {
        return auth.verifyOtp(body.challengeId(), body.otp(), request.getRemoteAddr(),
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
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() != null) {
            auth.logout(authentication.getCredentials().toString());
        }
        return ResponseEntity.noContent().build();
    }
}
