package in.gov.slate.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import in.gov.slate.common.ApiException;
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

    @Test
    void acceptsIndividualOwnerWithMobile() {
        var owner = new PropertyService.OwnerInput("INDIVIDUAL", "R. Kumar", "123412341234", "ABCDE1234F",
                "9876543210", "12 Main Road, Adyar", null, null);
        assertThatCode(() -> PropertyService.validateOwners(List.of(owner))).doesNotThrowAnyException();
    }

    @Test
    void rejectsIndividualOwnerWithoutMobile() {
        var owner = new PropertyService.OwnerInput("INDIVIDUAL", "R. Kumar", "123412341234", "ABCDE1234F",
                null, "12 Main Road, Adyar", null, null);
        assertThatThrownBy(() -> PropertyService.validateOwners(List.of(owner)))
                .isInstanceOf(ApiException.class).hasMessageContaining("mobile");
    }

    @Test
    void companyOwnerRequiresValidCinAndAuthorisedSignatory() {
        var signatory = new PropertyService.RepresentativeInput("S. Rao", "Director", "123412341234",
                "ABCDE1234F", "9876543210");
        var company = new PropertyService.OwnerInput("PRIVATE_LIMITED_COMPANY", "Acme Pvt Ltd", null, "AAACA1234C",
                null, "Guindy, Chennai", "U72900TN2010PTC123456", signatory);
        assertThatCode(() -> PropertyService.validateOwners(List.of(company))).doesNotThrowAnyException();

        var badCin = new PropertyService.OwnerInput("PRIVATE_LIMITED_COMPANY", "Acme Pvt Ltd", null, "AAACA1234C",
                null, "Guindy, Chennai", "12345", signatory);
        assertThatThrownBy(() -> PropertyService.validateOwners(List.of(badCin)))
                .isInstanceOf(ApiException.class).hasMessageContaining("CIN");

        var noSignatory = new PropertyService.OwnerInput("PUBLIC_LIMITED_COMPANY", "Acme Ltd", null, "AAACA1234C",
                null, "Guindy, Chennai", "L72900TN2010PLC123456", null);
        assertThatThrownBy(() -> PropertyService.validateOwners(List.of(noSignatory)))
                .isInstanceOf(ApiException.class).hasMessageContaining("authorised signatory");
    }

    @Test
    void hufOwnerRequiresKartaWithoutMobile() {
        var karta = new PropertyService.RepresentativeInput("V. Iyer", null, "123412341234", "ABCDE1234F", null);
        var huf = new PropertyService.OwnerInput("HUF", "Iyer HUF", null, "AAAHI1234H", null, "Mylapore", null, karta);
        assertThatCode(() -> PropertyService.validateOwners(List.of(huf))).doesNotThrowAnyException();
    }

    @Test
    void llpOwnerRequiresLlpin() {
        var partner = new PropertyService.RepresentativeInput("P. Das", null, "123412341234", "ABCDE1234F",
                "9876543210");
        var llp = new PropertyService.OwnerInput("LLP", "Das LLP", null, "AAAFD1234L", null, "T Nagar", "AAB1234",
                partner);
        assertThatThrownBy(() -> PropertyService.validateOwners(List.of(llp)))
                .isInstanceOf(ApiException.class).hasMessageContaining("LLPIN");
    }

    @Test
    void unspecifiedOwnerTypesUseDefaultFields() {
        var society = new PropertyService.OwnerInput("SOCIETY", "Adyar Co-op Society", "123412341234", "AAAAS1234S",
                null, "Adyar", null, null);
        assertThatCode(() -> PropertyService.validateOwners(List.of(society))).doesNotThrowAnyException();

        var unknown = new PropertyService.OwnerInput("ALIEN", "X", "123412341234", "AAAAS1234S", null, "Adyar",
                null, null);
        assertThatThrownBy(() -> PropertyService.validateOwners(List.of(unknown)))
                .isInstanceOf(ApiException.class).hasMessageContaining("owner type");
    }
}
