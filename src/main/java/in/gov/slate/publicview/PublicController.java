package in.gov.slate.publicview;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.ApiException;

/**
 * Common public view. It reads only rpt.public_property_view, which cannot
 * expose Aadhaar, consideration, contact details or rule-check findings.
 * Registered ownership and Revenue ownership are returned as separate facts.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final NamedParameterJdbcTemplate jdbc;

    public PublicController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/properties")
    public List<Map<String, Object>> search(@RequestParam(required = false) String surveyNo,
                                            @RequestParam(required = false) String village,
                                            @RequestParam(required = false) String ulpin,
                                            @RequestParam(required = false) String tokenRef,
                                            @RequestParam(defaultValue = "25") int limit) {
        return jdbc.queryForList("""
                SELECT * FROM rpt.public_property_view
                 WHERE (CAST(:surveyNo AS text) IS NULL OR survey_no = :surveyNo)
                   AND (CAST(:village AS text) IS NULL OR upper(village_code) = upper(:village))
                   AND (CAST(:ulpin AS text) IS NULL OR ulpin = :ulpin)
                   AND (CAST(:tokenRef AS text) IS NULL OR token_ref = :tokenRef)
                 ORDER BY property_ref
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("surveyNo", surveyNo)
                .addValue("village", village)
                .addValue("ulpin", ulpin)
                .addValue("tokenRef", tokenRef)
                .addValue("limit", limit));
    }

    @GetMapping("/properties/{propertyRef}")
    public Map<String, Object> property(@PathVariable String propertyRef) {
        var rows = jdbc.queryForList("SELECT * FROM rpt.public_property_view WHERE property_ref = :ref",
                new MapSqlParameterSource("ref", propertyRef));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Property " + propertyRef);
        }
        return rows.get(0);
    }

    @GetMapping("/tokens/{tokenRef}/history")
    public List<Map<String, Object>> tokenHistory(@PathVariable String tokenRef) {
        return jdbc.queryForList("""
                SELECT token_ref, property_ref, state_version, operation, owner_set_hash_hex, state_hash_hex,
                       prev_state_hash_hex, evidence_root_hex, onchain_tx_hash, recorded_at
                  FROM rpt.token_history_view WHERE token_ref = :tokenRef ORDER BY state_version
                """, new MapSqlParameterSource("tokenRef", tokenRef));
    }
}
