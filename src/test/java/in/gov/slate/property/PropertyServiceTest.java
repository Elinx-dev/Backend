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
import in.gov.slate.location.LocationService;

@ExtendWith(MockitoExtension.class)
class PropertyServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private NumberingService numbering;
    @Mock
    private AuditService audit;
    @Mock
    private LocationService locations;

    private PropertyService service;

    @BeforeEach
    void setUp() {
        service = new PropertyService(jdbc, numbering, audit, locations);
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
        Map<String, Object> measurement = Map.of(
            "seq", 1,
            "from_point", "NORTH",
            "to_point", "NORTH_EAST",
            "value", 125.5,
            "unit", "SQ_FT");
        Map<String, Object> history = Map.of(
            "id", 21L,
            "seq", 1,
            "executor_name", "Prior Owner",
            "claimant_name", "New Owner",
            "transaction_date", "2024-01-15",
            "nature_of_transaction", "SALE_FULL",
            "reference_no", "REG-42",
            "survey_no", "15");

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
            if (sql.contains("FROM core.property_measurement")) {
                return List.of(measurement);
            }
            if (sql.contains("SELECT id, seq, executor_name")) {
                return List.of(history);
            }
            return List.of();
        });

        Map<String, Object> result = service.get("TN-CHENNAI-00000015");

        assertThat(result.get("transactions")).isEqualTo(List.of(transaction));
        assertThat(result.get("propertyRelations")).isEqualTo(List.of(relation));
        assertThat(result.get("boundaryMeasurements")).isEqualTo(List.of(measurement));
        assertThat(result.get("chainOfTitle")).isEqualTo(List.of(Map.of(
            "id", 21L,
            "seq", 1,
            "executor_name", "Prior Owner",
            "claimant_name", "New Owner",
            "transaction_date", "2024-01-15",
            "nature_of_transaction", "SALE_FULL",
            "reference_no", "REG-42",
            "survey_no", "15")));
    }
}
