package in.gov.slate.security;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;

@Component
public class AdminStateScope {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminStateScope(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String resolve(String requestedState) {
        CurrentUser user = CurrentUser.require();
        String target = requestedState == null || requestedState.isBlank()
                ? user.stateCode() : requestedState.trim();
        if (user.hasRole("CENTRAL_ADMIN")) {
            requireConfiguredState(target);
            return target;
        }
        if (!user.hasRole("STATE_ADMIN")) {
            throw ApiException.forbidden("An administrator role is required");
        }
        if (!user.stateCode().equals(target)) {
            throw ApiException.forbidden("State administrators can only access their assigned state");
        }
        return user.stateCode();
    }

    public String resolveDashboardState(String requestedState) {
        CurrentUser user = CurrentUser.require();
        String target = requestedState == null || requestedState.isBlank()
                ? user.stateCode() : requestedState.trim();
        if (user.hasRole("CENTRAL_ADMIN") && "ALL".equals(target)) {
            return target;
        }
        return resolve(target);
    }

    public List<Map<String, Object>> states() {
        CurrentUser user = CurrentUser.require();
        if (user.hasRole("CENTRAL_ADMIN")) {
            return jdbc.queryForList("""
                    SELECT state_code AS code, state_name AS name
                      FROM cfg.state WHERE active ORDER BY state_name
                    """, new MapSqlParameterSource());
        }
        return jdbc.queryForList("""
                SELECT state_code AS code, state_name AS name
                  FROM cfg.state WHERE state_code = :stateCode AND active
                """, new MapSqlParameterSource("stateCode", user.stateCode()));
    }

    private void requireConfiguredState(String stateCode) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM cfg.state WHERE state_code = :stateCode AND active
            """, new MapSqlParameterSource("stateCode", stateCode), Long.class);
        if (count == null || count == 0L) {
            throw ApiException.badRequest("Unknown state code " + stateCode);
        }
    }
}