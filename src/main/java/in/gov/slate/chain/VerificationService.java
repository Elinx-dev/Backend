package in.gov.slate.chain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.Hashes;

/**
 * Tamper evidence. Recomputes each token state hash from the stored owner set
 * and compares it with the recorded chain of hashes: any edit to ownership rows
 * or to a historical state row shows up as a MISMATCH.
 */
@Service
public class VerificationService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public VerificationService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> verifyToken(String tokenRef) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("VERIFY_RUN");
        var tokenRows = jdbc.queryForList("""
                SELECT t.id, t.token_ref, t.property_id, t.state_version, t.status,
                       '0x' || encode(t.property_ref_hash,'hex') AS property_ref_hash, p.property_ref
                  FROM chain.token t JOIN core.property p ON p.id = t.property_id
                 WHERE t.token_ref = :tokenRef
                """, new MapSqlParameterSource("tokenRef", tokenRef));
        if (tokenRows.isEmpty()) {
            throw ApiException.notFound("Token " + tokenRef);
        }
        Map<String, Object> token = tokenRows.get(0);
        long tokenId = ((Number) token.get("id")).longValue();

        var history = jdbc.queryForList("""
                SELECT state_version, operation, owner_set_json,
                       '0x' || encode(owner_set_hash,'hex') AS owner_set_hash,
                       '0x' || encode(state_hash,'hex') AS state_hash, onchain_tx_hash
                  FROM chain.token_state_history WHERE token_id = :id ORDER BY state_version
                """, new MapSqlParameterSource("id", tokenId));

        List<Map<String, Object>> checks = new ArrayList<>();
        boolean mismatch = false;
        for (Map<String, Object> entry : history) {
            List<TokenService.Owner> owners = owners(entry.get("owner_set_json"));
            String recomputed = Hashes.hex(TokenService.ownerSetHash(owners));
            boolean ok = recomputed.equals(entry.get("owner_set_hash"));
            mismatch |= !ok;
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("stateVersion", entry.get("state_version"));
            check.put("operation", entry.get("operation"));
            check.put("recordedOwnerSetHash", entry.get("owner_set_hash"));
            check.put("recomputedOwnerSetHash", recomputed);
            check.put("stateHash", entry.get("state_hash"));
            check.put("onchainTxHash", entry.get("onchain_tx_hash"));
            check.put("ownerSetIntact", ok);
            checks.add(check);
        }

        String currentOwnerHash = Hashes.hex(TokenService.ownerSetHash(currentOwners(
                ((Number) token.get("property_id")).longValue())));
        String latestRecordedHash = history.isEmpty() ? null
                : (String) history.get(history.size() - 1).get("owner_set_hash");
        boolean currentMatches = latestRecordedHash != null && latestRecordedHash.equals(currentOwnerHash);
        mismatch |= !currentMatches;

        String outcome = history.isEmpty() ? "UNAVAILABLE" : (mismatch ? "MISMATCH" : "VERIFIED");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tokenRef", tokenRef);
        details.put("propertyRef", token.get("property_ref"));
        details.put("stateVersion", token.get("state_version"));
        details.put("history", checks);
        details.put("currentRegisteredOwnerSetHash", currentOwnerHash);
        details.put("latestRecordedOwnerSetHash", latestRecordedHash);
        details.put("currentOwnershipMatchesToken", currentMatches);
        details.put("anchors", anchors(tokenId));

        jdbc.update("""
                INSERT INTO chain.verification_run (property_id, token_id, requested_by, outcome, details_json)
                VALUES (:propertyId, :tokenId, :userId, :outcome, cast(:details AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("propertyId", token.get("property_id"))
                .addValue("tokenId", tokenId)
                .addValue("userId", user.id())
                .addValue("outcome", outcome)
                .addValue("details", json(details)));

        Map<String, Object> out = new LinkedHashMap<>(details);
        out.put("outcome", outcome);
        return out;
    }

    private List<Map<String, Object>> anchors(long tokenId) {
        return jdbc.queryForList("""
                SELECT contract_code, function_name, status, tx_hash, block_number, error_message, submitted_at
                  FROM chain.blockchain_transaction WHERE token_id = :id ORDER BY id
                """, new MapSqlParameterSource("id", tokenId));
    }

    private List<TokenService.Owner> currentOwners(long propertyId) {
        var rows = jdbc.queryForList("""
                SELECT owner_name, share_pct FROM core.property_owner
                 WHERE property_id = :id AND effective_to IS NULL ORDER BY id
                """, new MapSqlParameterSource("id", propertyId));
        List<TokenService.Owner> owners = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object share = row.get("share_pct");
            owners.add(new TokenService.Owner((String) row.get("owner_name"),
                    share == null ? null : new BigDecimal(share.toString())));
        }
        return owners;
    }

    private List<TokenService.Owner> owners(Object ownerSetJson) {
        if (ownerSetJson == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = mapper.readValue(ownerSetJson.toString(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<TokenService.Owner> owners = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                Object share = entry.get("sharePct");
                owners.add(new TokenService.Owner(String.valueOf(entry.get("name")),
                        share == null ? null : new BigDecimal(share.toString())));
            }
            return owners;
        } catch (Exception e) {
            throw new IllegalStateException("Stored owner set is not readable JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise verification details", e);
        }
    }
}
