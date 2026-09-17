package in.gov.slate.common;

import java.util.Set;

import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated officer behind the current request. */
public record CurrentUser(long id, String username, String fullName, String stateCode,
                          String department, Set<String> roles, Set<String> permissions,
                          Set<String> sroCodes, Set<String> villageCodes) {

    public static CurrentUser require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            throw ApiException.forbidden("No authenticated user on this request");
        }
        return user;
    }

    public static CurrentUser orNull() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof CurrentUser user) ? user : null;
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public void requirePermission(String permission) {
        if (!permissions.contains(permission)) {
            throw ApiException.forbidden("Permission " + permission + " is required for this action");
        }
    }
}
