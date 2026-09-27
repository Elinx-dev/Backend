package in.gov.slate.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.NumberingService;

@ExtendWith(MockitoExtension.class)
class PropertyServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private NumberingService numbering;
    @Mock
    private AuditService audit;

    private PropertyService service;

    @BeforeEach
    void setUp() {
        service = new PropertyService(jdbc, numbering, audit);
        CurrentUser user = new CurrentUser(1L, "ro.adyar", "R. Anandhi", "TN", "REGISTRATION",
                Set.of("REGISTRATION_OFFICER"), Set.of("PROPERTY_READ"), Set.of("ADYAR"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsGridReadyRegistrationHistoryAndPropertyRelations() {
        Map<String, Object> transaction = Map.of(
                "txn_ref", "TXN-TN-2026-000015",
                "old_owner_names", "Old Owner",
                "new_owner_names", "New Owner",
                "declared_consideration", 500000,
                "guideline_value_at_registration", 450000,
                "registration_fee", 5000,
                "token_ref", "SLATE-TN-00000004",
                "state_hash_hex", "abc123",
                "blockchain_anchor_status", "MINED");
        Map<String, Object> relation = Map.of(
                "relation_type", "PARENT",
                "property_ref", "TN-CHENNAI-00000001",
                "token_ref", "SLATE-TN-00000001");

        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT p.*, t.token_ref")) {
                return List.of(Map.of(
                        "id", 15L,
                        "property_ref", "TN-CHENNAI-00000015",
                        "is_apartment_unit", false));
            }
            if (sql.contains("history_source")) {
                return List.of(transaction);
            }
            if (sql.contains("relation_type, property_ref")) {
                return List.of(relation);
            }
            return List.of();
        });

        Map<String, Object> result = service.get("TN-CHENNAI-00000015");

        assertThat(result.get("transactions")).isEqualTo(List.of(transaction));
        assertThat(result.get("propertyRelations")).isEqualTo(List.of(relation));
    }
}
