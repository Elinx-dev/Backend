package in.gov.slate.common;

import java.time.Year;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Generates references from cfg.numbering_series patterns. */
@Service
public class NumberingService {

    private final NamedParameterJdbcTemplate jdbc;

    public NumberingService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public String next(String stateCode, String seriesCode, String districtCode) {
        var params = new MapSqlParameterSource()
                .addValue("stateCode", stateCode)
                .addValue("seriesCode", seriesCode);
        Long value = jdbc.queryForObject("""
                UPDATE cfg.numbering_series
                   SET current_value = current_value + 1
                 WHERE state_code = :stateCode AND series_code = :seriesCode
                RETURNING current_value
                """, params, Long.class);
        String pattern = jdbc.queryForObject(
                "SELECT pattern FROM cfg.numbering_series WHERE state_code = :stateCode AND series_code = :seriesCode",
                params, String.class);
        if (pattern == null || value == null) {
            throw ApiException.badRequest("Numbering series " + seriesCode + " is not configured for " + stateCode);
        }
        return render(pattern, value, districtCode);
    }

    private String render(String pattern, long value, String districtCode) {
        String out = pattern
                .replace("{YEAR}", String.valueOf(Year.now().getValue()))
                .replace("{DISTRICT}", districtCode == null ? "XXX" : districtCode);
        int start = out.indexOf("{SEQ:");
        if (start >= 0) {
            int end = out.indexOf('}', start);
            int width = Integer.parseInt(out.substring(start + 5, end));
            out = out.substring(0, start) + String.format("%0" + width + "d", value) + out.substring(end + 1);
        }
        return out;
    }
}
