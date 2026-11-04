package in.gov.slate.publicview;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
 *
 * A search must name one property: either its Property ID / ULPIN, or the full
 * Survey no + Subdivision + District + Taluk + Village. At most one record is
 * returned, so the public page can't be used to list the register. Every input
 * is checked against a strict pattern and only ever bound as a SQL parameter.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    static final int LOOKUP_MIN_CHARS = 3;
    private static final int LOOKUP_LIMIT = 10;

    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{2,39}");
    private static final Pattern SURVEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/-]{0,19}");
    private static final Pattern PLACE = Pattern.compile("(?!.*--)[A-Za-z0-9][A-Za-z0-9 .()-]{0,59}");
    private static final Pattern DOCUMENT_NO = Pattern.compile("[A-Za-z0-9/_-]{1,64}");

    private static final String SEARCH_COLUMNS = """
            v.property_ref, v.ulpin, v.property_type_code, v.land_type_code, v.classification_code,
            v.extent_value, v.extent_unit, v.survey_no, v.subdivision_no,
            v.district_code, d.district_name, v.taluk_code, tk.taluk_name, v.village_code, rv.village_name,
            v.property_status
            """;

    private static final String NAMES_JOIN = """
            LEFT JOIN master.registration_district d
                   ON d.state_code = v.state_code AND d.district_code = v.district_code
            LEFT JOIN LATERAL (SELECT t.taluk_name FROM master.taluk t
                                 JOIN master.sub_registrar_office s ON s.id = t.sro_id
                                WHERE s.district_id = d.id AND t.taluk_code = v.taluk_code LIMIT 1) tk ON TRUE
            LEFT JOIN LATERAL (SELECT r.village_name FROM master.revenue_village r
                                 JOIN master.taluk t ON t.id = r.taluk_id
                                 JOIN master.sub_registrar_office s ON s.id = t.sro_id
                                WHERE s.district_id = d.id AND t.taluk_code = v.taluk_code
                                  AND r.village_code = v.village_code LIMIT 1) rv ON TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PublicController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the one matching property (a list of 0 or 1 rows). */
    @GetMapping("/properties")
    public List<Map<String, Object>> search(@RequestParam(required = false) String propertyRef,
                                            @RequestParam(required = false) String ulpin,
                                            @RequestParam(required = false) String surveyNo,
                                            @RequestParam(required = false) String subdivisionNo,
                                            @RequestParam(required = false) String district,
                                            @RequestParam(required = false) String taluk,
                                            @RequestParam(required = false) String village) {
        String ref = optional(propertyRef, REF, "Property ID");
        String ulpinValue = optional(ulpin, REF, "ULPIN");
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where;
        if (ref != null || ulpinValue != null) {
            where = "(CAST(:ref AS text) IS NULL OR upper(v.property_ref) = upper(:ref))"
                    + " AND (CAST(:ulpin AS text) IS NULL OR upper(v.ulpin) = upper(:ulpin))";
            params.addValue("ref", ref).addValue("ulpin", ulpinValue);
        } else {
            String survey = required(surveyNo, SURVEY, "Survey no");
            String subdivision = required(subdivisionNo, SURVEY, "Subdivision");
            String districtValue = required(district, PLACE, "District");
            String talukValue = required(taluk, PLACE, "Taluk");
            String villageValue = required(village, PLACE, "Village");
            where = """
                    upper(v.survey_no) = upper(:surveyNo)
                    AND upper(v.subdivision_no) = upper(:subdivisionNo)
                    AND (upper(v.district_code) = upper(:district) OR upper(d.district_name) = upper(:district))
                    AND (upper(v.taluk_code) = upper(:taluk) OR upper(tk.taluk_name) = upper(:taluk))
                    AND (upper(v.village_code) = upper(:village) OR upper(rv.village_name) = upper(:village))
                    """;
            params.addValue("surveyNo", survey).addValue("subdivisionNo", subdivision)
                    .addValue("district", districtValue).addValue("taluk", talukValue)
                    .addValue("village", villageValue);
        }
        return jdbc.queryForList("SELECT " + SEARCH_COLUMNS + " FROM rpt.public_property_view v " + NAMES_JOIN
                + " WHERE " + where
                + " ORDER BY (v.property_status = 'ACTIVE') DESC, v.property_ref DESC LIMIT 1", params);
    }

    @GetMapping("/properties/{propertyRef}")
    public Map<String, Object> property(@PathVariable String propertyRef) {
        String ref = required(propertyRef, REF, "Property ID");
        var rows = jdbc.queryForList("SELECT * FROM rpt.public_property_view WHERE property_ref = :ref",
                new MapSqlParameterSource("ref", ref));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Property " + ref);
        }
        return rows.get(0);
    }

    @GetMapping("/tokens/{tokenRef}/history")
    public List<Map<String, Object>> tokenHistory(@PathVariable String tokenRef) {
        return jdbc.queryForList("""
                SELECT token_ref, property_ref, state_version, operation, owner_set_hash_hex, state_hash_hex,
                       prev_state_hash_hex, evidence_root_hex, onchain_tx_hash, recorded_at
                  FROM rpt.token_history_view WHERE token_ref = :tokenRef ORDER BY state_version
                """, new MapSqlParameterSource("tokenRef", required(tokenRef, REF, "Token reference")));
    }

    /** Autocomplete for the District box: names or codes containing the typed text (3+ characters). */
    @GetMapping("/lookups/districts")
    public List<Map<String, Object>> districts(@RequestParam(required = false) String q) {
        String text = lookupText(q);
        if (text == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT d.district_code AS code, d.district_name AS name
                  FROM master.registration_district d
                 WHERE d.active AND (d.district_name ILIKE :like ESCAPE '\\' OR d.district_code ILIKE :like ESCAPE '\\')
                 ORDER BY d.district_name LIMIT :limit
                """, lookupParams(text));
    }

    /** Autocomplete for the Taluk box, inside the chosen district when one is given. */
    @GetMapping("/lookups/taluks")
    public List<Map<String, Object>> taluks(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String district) {
        String text = lookupText(q);
        if (text == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT DISTINCT t.taluk_code AS code, t.taluk_name AS name
                  FROM master.taluk t
                  JOIN master.sub_registrar_office s ON s.id = t.sro_id
                  JOIN master.registration_district d ON d.id = s.district_id
                 WHERE t.active AND (t.taluk_name ILIKE :like ESCAPE '\\' OR t.taluk_code ILIKE :like ESCAPE '\\')
                   AND (CAST(:district AS text) IS NULL OR upper(d.district_code) = upper(:district)
                        OR upper(d.district_name) = upper(:district))
                 ORDER BY name LIMIT :limit
                """, lookupParams(text).addValue("district", optional(district, PLACE, "District")));
    }

    /** Autocomplete for the Village box, inside the chosen district / taluk when given. */
    @GetMapping("/lookups/villages")
    public List<Map<String, Object>> villages(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) String district,
                                              @RequestParam(required = false) String taluk) {
        String text = lookupText(q);
        if (text == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT DISTINCT r.village_code AS code, r.village_name AS name
                  FROM master.revenue_village r
                  JOIN master.taluk t ON t.id = r.taluk_id
                  JOIN master.sub_registrar_office s ON s.id = t.sro_id
                  JOIN master.registration_district d ON d.id = s.district_id
                 WHERE r.active AND (r.village_name ILIKE :like ESCAPE '\\' OR r.village_code ILIKE :like ESCAPE '\\')
                   AND (CAST(:district AS text) IS NULL OR upper(d.district_code) = upper(:district)
                        OR upper(d.district_name) = upper(:district))
                   AND (CAST(:taluk AS text) IS NULL OR upper(t.taluk_code) = upper(:taluk)
                        OR upper(t.taluk_name) = upper(:taluk))
                 ORDER BY name LIMIT :limit
                """, lookupParams(text)
                .addValue("district", optional(district, PLACE, "District"))
                .addValue("taluk", optional(taluk, PLACE, "Taluk")));
    }

    private in.gov.slate.chain.ChainService chain;

    @org.springframework.beans.factory.annotation.Autowired
    void setChainService(in.gov.slate.chain.ChainService chain) {
        this.chain = chain;
    }

    /** Certificate QR target: checks the registered deed and the property token against the chain. No personal data. */
    @GetMapping("/verify")
    public Map<String, Object> verifyCertificate(@RequestParam String documentNo) {
        if (!DOCUMENT_NO.matcher(documentNo).matches()) {
            throw ApiException.badRequest("Invalid document number");
        }
        var rows = jdbc.queryForList("""
                SELECT r.registered_document_no, r.registration_date, r.registering_sro,
                       '0x' || encode(r.deed_sha256, 'hex') AS deed_sha256, tx.txn_ref, tx.state_code,
                       p.property_ref, p.status AS property_status, t.token_ref, t.status AS token_status,
                       (SELECT '0x' || encode(h.state_hash, 'hex') FROM chain.token_state_history h
                         WHERE h.token_id = t.id ORDER BY h.state_version DESC LIMIT 1) AS latest_state_hash
                  FROM core.registration_result r
                  JOIN core.transaction tx ON tx.id = r.transaction_id
                  JOIN core.property p ON p.id = tx.property_id
                  LEFT JOIN chain.token t ON t.id = p.token_id
                 WHERE r.registered_document_no = :doc
                """, new MapSqlParameterSource("doc", documentNo));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Registered document " + documentNo);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(rows.get(0));
        String stateCode = String.valueOf(out.remove("state_code")).trim();
        boolean connected = chain != null && chain.connected(stateCode);
        out.put("chainConnected", connected);
        String onChainDeed = connected ? chain.deedOf(stateCode, documentNo) : null;
        out.put("deedHashOnChain", onChainDeed);
        String deedStatus = onChainDeed == null ? "NOT_CONFIGURED" : onChainDeed.isEmpty() ? "NOT_ANCHORED"
                : onChainDeed.equalsIgnoreCase((String) out.get("deed_sha256")) ? "VERIFIED" : "MISMATCH";
        out.put("deedStatus", deedStatus);
        Object tokenRef = out.get("token_ref");
        Object stateHash = out.get("latest_state_hash");
        Boolean tokenOnChain = connected && tokenRef != null && stateHash != null
                ? chain.hasState(stateCode, (String) tokenRef, (String) stateHash) : null;
        out.put("tokenStatusOnChain", tokenOnChain == null ? "NOT_CONFIGURED" : tokenOnChain ? "VERIFIED" : "NOT_ANCHORED");
        return out;
    }

    private static String optional(String value, Pattern pattern, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!pattern.matcher(trimmed).matches()) {
            throw ApiException.badRequest(label + " has characters that are not allowed");
        }
        return trimmed;
    }

    private static String required(String value, Pattern pattern, String label) {
        String checked = optional(value, pattern, label);
        if (checked == null) {
            throw ApiException.badRequest("Enter the Property ID, or the Survey no, Subdivision, District, Taluk and Village"
                    + " (" + label + " is missing)");
        }
        return checked;
    }

    private static String lookupText(String q) {
        String text = optional(q, PLACE, "Search text");
        return text == null || text.length() < LOOKUP_MIN_CHARS ? null : text;
    }

    private static MapSqlParameterSource lookupParams(String text) {
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return new MapSqlParameterSource().addValue("like", "%" + escaped + "%").addValue("limit", LOOKUP_LIMIT);
    }
}
