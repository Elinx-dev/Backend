package in.gov.slate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;

@ExtendWith(MockitoExtension.class)
class AdminStateScopeTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void stateAdministratorIsRestrictedToAssignedState() {
        authenticate("STATE_ADMIN", "TN");

        assertEquals("TN", new AdminStateScope(jdbc).resolve(null));
        assertThrows(ApiException.class, () -> new AdminStateScope(jdbc).resolve("KA"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void centralAdministratorCanSelectAnActiveState() {
        authenticate("CENTRAL_ADMIN", "TN");
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1L);

        assertEquals("KA", new AdminStateScope(jdbc).resolve("KA"));
    }

    private void authenticate(String role, String stateCode) {
        CurrentUser user = new CurrentUser(1L, "admin", "Administrator", stateCode, "ADMIN",
                Set.of(role), Set.of(), Set.of(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
    }
}