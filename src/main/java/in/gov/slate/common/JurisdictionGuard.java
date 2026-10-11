package in.gov.slate.common;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Object-level access check for property records: the property must be in the officer's
 * state and inside one of the officer's assigned jurisdictions (SRO, village, taluk or
 * district). State and central administrators, and officers with no jurisdiction rows,
 * are limited to their state only.
 */
@Component
public class JurisdictionGuard {

    private final NamedParameterJdbcTemplate jdbc;

    public JurisdictionGuard(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean canAccess(CurrentUser user, Map<String, Object> property) {
        if (!Objects.equals(trim(property.get("state_code")), user.stateCode())) {
            return false;
        }
        if (user.hasRole("STATE_ADMIN") || user.hasRole("CENTRAL_ADMIN")) {
            return true;
        }
        List<Map<String, Object>> scopes = jdbc.queryForList("""
                SELECT district_code, taluk_code, village_code, sro_code
                  FROM sec.user_jurisdiction WHERE user_id = :userId
                """, new MapSqlParameterSource("userId", user.id()));
        return scopes.isEmpty() || scopes.stream().anyMatch(scope -> matches(scope, property));
    }

    static boolean matches(Map<String, Object> scope, Map<String, Object> property) {
        if (scope.get("sro_code") != null && Objects.equals(scope.get("sro_code"), property.get("sro_code"))) {
            return true;
        }
        if (scope.get("village_code") != null) {
            return Objects.equals(scope.get("village_code"), property.get("village_code"));
        }
        if (scope.get("taluk_code") != null) {
            return Objects.equals(scope.get("taluk_code"), property.get("taluk_code"));
        }
        return scope.get("district_code") != null
                && Objects.equals(scope.get("district_code"), property.get("district_code"));
    }

    /** Throws 404 (not 403) so a guessed reference does not reveal that a record exists. */
    public void require(CurrentUser user, Map<String, Object> property, String what) {
        if (!canAccess(user, property)) {
            throw ApiException.notFound(what);
        }
    }

    private static String trim(Object value) {
        return value == null ? null : value.toString().trim();
    }
}
