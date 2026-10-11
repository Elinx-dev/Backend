package in.gov.slate.security;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditEvent;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('STATE_ADMIN', 'CENTRAL_ADMIN')")
public class AdminController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final AdminStateScope stateScope;

    public AdminController(UserRepository users, PasswordEncoder encoder, AuditService audit,
                           AdminStateScope stateScope) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.stateScope = stateScope;
    }

    public record UserRequest(@NotBlank @Size(max = 320) String username,
                              @NotBlank @Size(max = 200) String fullName,
                              @Size(max = 320) String email, @Size(max = 40) String mobile,
                              @Size(max = 120) String designation, @NotBlank String department,
                              @NotBlank String status, boolean mfaRequired,
                              @NotEmpty List<String> roles, @Size(min = 8, max = 128) String password) {
    }

    @GetMapping("/states")
    public List<Map<String, Object>> states() {
        return stateScope.states();
    }

    @GetMapping("/users")
    public Map<String, Object> users(@RequestParam(required = false) String stateCode) {
        String targetState = stateScope.resolve(stateCode);
        List<Map<String, Object>> roles = users.roles();
        if (!CurrentUser.require().hasRole("CENTRAL_ADMIN")) {
            roles = roles.stream().filter(role -> !"CENTRAL_ADMIN".equals(role.get("code"))).toList();
        }
        return Map.of("users", users.adminUsers(targetState), "roles", roles);
    }

    @PostMapping("/users")
    public Map<String, Object> create(@RequestParam(required = false) String stateCode,
                                      @Valid @RequestBody UserRequest request) {
        CurrentUser current = CurrentUser.require();
        String targetState = stateScope.resolve(stateCode);
        if (!current.hasRole("CENTRAL_ADMIN") && request.roles().contains("CENTRAL_ADMIN")) {
            throw ApiException.forbidden("Only a central administrator can assign the central administrator role");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_REQUIRED", "A temporary password is required");
        }
        long id = users.createUser(targetState, request.username().trim(), request.fullName().trim(),
                blankToNull(request.email()), blankToNull(request.mobile()), blankToNull(request.designation()),
                request.department(), encoder.encode(request.password()), request.status(), request.mfaRequired(),
                request.roles());
        audit.record(AuditEvent.of("USER_CREATED").stateCode(targetState)
                .category(AuditEvent.CATEGORY_SECURITY)
                .entity("USER", String.valueOf(id))
                .after(snapshot(request))
                .detail("Created user " + request.username().trim()));
        return Map.of("id", id);
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestParam(required = false) String stateCode,
                                     @Valid @RequestBody UserRequest request) {
        CurrentUser current = CurrentUser.require();
        String targetState = stateScope.resolve(stateCode);
        String requiredRole = current.hasRole("CENTRAL_ADMIN") ? "CENTRAL_ADMIN" : "STATE_ADMIN";
        if (request.roles().contains("CENTRAL_ADMIN") && !current.hasRole("CENTRAL_ADMIN")) {
            throw ApiException.forbidden("Only a central administrator can assign the central administrator role");
        }
        if (id == current.id() && !request.roles().contains(requiredRole)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_ROLE_REQUIRED", "You cannot remove your own administrator role");
        }
        UserRepository.UserRow user = users.findById(id)
                .orElseThrow(() -> ApiException.notFound("User " + id));
        if (!targetState.equals(user.stateCode())) {
            throw ApiException.notFound("User " + id);
        }
        users.updateUser(id, targetState, request.fullName().trim(), blankToNull(request.email()),
                blankToNull(request.mobile()), blankToNull(request.designation()), request.department(),
                request.status(), request.mfaRequired(), request.roles());
        audit.record(AuditEvent.of("USER_UPDATED").stateCode(targetState)
                .category(AuditEvent.CATEGORY_SECURITY)
                .entity("USER", String.valueOf(id))
                .before(snapshot(user))
                .after(snapshot(request))
                .detail("Updated user " + user.username()));
        return Map.of("id", id);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Audit payload for a user account; credentials are never part of it. */
    private Map<String, Object> snapshot(UserRequest request) {
        return Map.of("username", request.username().trim(), "fullName", request.fullName().trim(),
                "department", request.department(), "status", request.status(),
                "mfaRequired", request.mfaRequired(), "roles", request.roles());
    }

    private Map<String, Object> snapshot(UserRepository.UserRow user) {
        return Map.of("username", String.valueOf(user.username()), "fullName", String.valueOf(user.fullName()),
                "department", String.valueOf(user.department()), "status", String.valueOf(user.status()),
                "mfaRequired", user.mfaRequired());
    }
}