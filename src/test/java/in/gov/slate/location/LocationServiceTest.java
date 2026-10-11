package in.gov.slate.location;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import in.gov.slate.common.ApiException;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private static List<Map<String, Object>> codes(String... values) {
        return Arrays.stream(values).map(v -> Map.<String, Object>of("code", v, "name", v)).toList();
    }

    @Test
    void acceptsMappedHierarchy() {
        when(jdbc.queryForList(contains("FROM master.registration_district"), any(SqlParameterSource.class)))
                .thenReturn(codes("CHN"));
        when(jdbc.queryForList(contains("FROM master.sub_registrar_office"), any(SqlParameterSource.class)))
                .thenReturn(codes("SRO-ADYAR", "SRO-SHOL"));
        when(jdbc.queryForList(contains("FROM master.taluk"), any(SqlParameterSource.class)))
                .thenReturn(codes("SHOL"));
        when(jdbc.queryForList(contains("FROM master.revenue_village"), any(SqlParameterSource.class)))
                .thenReturn(codes("PERUNGUDI"));

        LocationService service = new LocationService(jdbc);

        assertThatCode(() -> service.requireValidPath("TN", "CHN", "SRO-ADYAR", "SHOL", "PERUNGUDI"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSroNotMappedToDistrict() {
        when(jdbc.queryForList(contains("FROM master.registration_district"), any(SqlParameterSource.class)))
                .thenReturn(codes("CHN"));
        when(jdbc.queryForList(contains("FROM master.sub_registrar_office"), any(SqlParameterSource.class)))
                .thenReturn(codes("SRO-ADYAR"));

        LocationService service = new LocationService(jdbc);

        assertThatThrownBy(() -> service.requireValidPath("TN", "CHN", "SRO-KELAM", "CHENGALPATTU", "KELAMBAKKAM"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sub-Registrar Office SRO-KELAM is not mapped to district CHN");
    }
}
