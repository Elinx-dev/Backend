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

    private in.gov.slate.chain.ChainService chain;

    @org.springframework.beans.factory.annotation.Autowired
    void setChainService(in.gov.slate.chain.ChainService chain) {
        this.chain = chain;
    }

    /** Certificate QR target: checks the registered deed and the property token against the chain. No personal data. */
    @GetMapping("/verify")
    public Map<String, Object> verifyCertificate(@RequestParam String documentNo) {
        if (!documentNo.matches("[A-Za-z0-9/_-]{1,64}")) {
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

}
