package in.gov.slate.publicview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import in.gov.slate.common.ApiException;

class PublicControllerTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PublicController controller = new PublicController(jdbc);

    @Test
    void searchNeedsPropertyIdOrEveryLocationField() {
        assertThatThrownBy(() -> controller.search(null, null, null, null, null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.search(null, null, "112", "2A", "CHN", "SHOL", null))
                .isInstanceOf(ApiException.class).hasMessageContaining("Village");
        verify(jdbc, never()).queryForList(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void injectionTextIsRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> controller.search("' OR '1'='1", null, null, null, null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.search(null, null, "112' OR 1=1", "2A", "CHN", "SHOL", "PERUNGUDI"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.search(null, null, "112", "2A", "CHN; DROP TABLE x", "SHOL", "PERUNGUDI"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.search(null, null, "112", "2A", "CHN -- x", "SHOL", "PERUNGUDI"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.districts("a' UNION SELECT 1"))
                .isInstanceOf(ApiException.class);
        verify(jdbc, never()).queryForList(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void searchReturnsAtMostOneRowAndBindsValues() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        controller.search(null, null, " 112 ", "2A", "Chennai", "Sholinganallur", "Perungudi");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).endsWith("LIMIT 1").doesNotContain("112").doesNotContain("token_ref");
        assertThat(((MapSqlParameterSource) params.getValue()).getValue("surveyNo")).isEqualTo("112");
    }

    @Test
    void lookupsNeedThreeCharactersAndEscapeWildcards() {
        assertThat(controller.districts("ch")).isEmpty();
        verify(jdbc, never()).queryForList(anyString(), any(SqlParameterSource.class));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        controller.villages("per", "CHN", null);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForList(anyString(), params.capture());
        assertThat(((MapSqlParameterSource) params.getValue()).getValue("like")).isEqualTo("%per%");
    }
}
