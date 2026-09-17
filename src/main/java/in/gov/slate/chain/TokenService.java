package in.gov.slate.chain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.Hashes;
import in.gov.slate.common.NumberingService;

/**
 * Token lifecycle. A token is minted when a property's first transaction reaches
 * REGISTERED; later registrations update the same token; a survey that splits a
 * parcel creates child tokens and supersedes the parent.
 */
@Service
public class TokenService {

    public record Owner(String name, BigDecimal sharePct) {
    }

    public record ChildParcel(String propertyRef, long propertyId, List<Owner> owners) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final NumberingService numbering;
    private final ChainService chain;
    private final ObjectMapper mapper;

    public TokenService(NamedParameterJdbcTemplate jdbc, NumberingService numbering, ChainService chain,
                        ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.numbering = numbering;
        this.chain = chain;
        this.mapper = mapper;
    }

    public static byte[] ownerSetHash(List<Owner> owners) {
        String canonical = owners.stream()
                .sorted(Comparator.comparing(o -> o.name().toUpperCase()))
                .map(o -> o.name().trim().toUpperCase() + ":" + (o.sharePct() == null ? "" : o.sharePct().stripTrailingZeros().toPlainString()))
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
        return Hashes.sha256(canonical);
    }

    private static byte[] stateHash(String tokenRef, int version, byte[] propertyRefHash, byte[] ownerSetHash,
                                    byte[] prevStateHash, byte[] evidenceRoot) {
        var buffer = new StringBuilder()
                .append(tokenRef).append('|').append(version).append('|')
                .append(Hashes.hex(propertyRefHash)).append('|')
                .append(Hashes.hex(ownerSetHash)).append('|')
                .append(prevStateHash == null ? "" : Hashes.hex(prevStateHash)).append('|')
                .append(evidenceRoot == null ? "" : Hashes.hex(evidenceRoot));
        return Hashes.sha256(buffer.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Mints on first registration, updates on every later registration. Returns the
     * token row as stored, including the operation that was applied.
     */
    @Transactional
    public Map<String, Object> mintOrUpdate(String stateCode, long propertyId, String propertyRef,
                                            long transactionId, List<Owner> owners, byte[] evidenceRoot) {
        var existing = jdbc.queryForList("""
                SELECT id, token_ref, state_version, onchain_token_id
                  FROM chain.token WHERE property_id = :propertyId AND status = 'ACTIVE'
                """, new MapSqlParameterSource("propertyId", propertyId));

        byte[] propertyRefHash = Hashes.sha256(propertyRef);
        byte[] ownerHash = ownerSetHash(owners);

        if (existing.isEmpty()) {
            String tokenRef = numbering.next(stateCode, "TOKEN_REF", null);
            byte[] state = stateHash(tokenRef, 1, propertyRefHash, ownerHash, null, evidenceRoot);
            var keyHolder = new GeneratedKeyHolder();
            jdbc.update("""
                    INSERT INTO chain.token (state_code, token_ref, onchain_token_id, property_id, state_version,
                        status, property_ref_hash, owner_set_hash, evidence_root, minted_txn_id)
                    VALUES (:stateCode, :tokenRef, NULL, :propertyId, 1, 'ACTIVE', :propertyRefHash, :ownerHash,
                        :evidenceRoot, :txnId)
                    """, new MapSqlParameterSource()
                    .addValue("stateCode", stateCode)
                    .addValue("tokenRef", tokenRef)
                    .addValue("propertyId", propertyId)
                    .addValue("propertyRefHash", propertyRefHash)
                    .addValue("ownerHash", ownerHash)
                    .addValue("evidenceRoot", evidenceRoot)
                    .addValue("txnId", transactionId), keyHolder, new String[]{"id"});
            long tokenId = keyHolder.getKey().longValue();
            jdbc.update("UPDATE core.property SET token_id = :tokenId WHERE id = :propertyId",
                    new MapSqlParameterSource().addValue("tokenId", tokenId).addValue("propertyId", propertyId));
            recordHistory(tokenId, 1, "MINT", transactionId, ownerHash, owners, evidenceRoot, null, state);
            anchor(stateCode, tokenId, tokenRef, transactionId, state, "MINT");
            return tokenState(tokenId, "MINT");
        }

        Map<String, Object> token = existing.get(0);
        long tokenId = ((Number) token.get("id")).longValue();
        String tokenRef = (String) token.get("token_ref");
        int version = ((Number) token.get("state_version")).intValue() + 1;
        byte[] prevState = previousStateHash(tokenId);
        byte[] state = stateHash(tokenRef, version, propertyRefHash, ownerHash, prevState, evidenceRoot);

        jdbc.update("""
                UPDATE chain.token SET state_version = :version, owner_set_hash = :ownerHash,
                       evidence_root = :evidenceRoot, prev_state_hash = :prevState
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", tokenId)
                .addValue("version", version)
                .addValue("ownerHash", ownerHash)
                .addValue("evidenceRoot", evidenceRoot)
                .addValue("prevState", prevState));
        recordHistory(tokenId, version, "UPDATE", transactionId, ownerHash, owners, evidenceRoot, prevState, state);
        anchor(stateCode, tokenId, tokenRef, transactionId, state, "UPDATE");
        return tokenState(tokenId, "UPDATE");
    }

    /** Survey-driven split: the parent is superseded and one child token is minted per resulting parcel. */
    @Transactional
    public List<Map<String, Object>> split(String stateCode, long parentPropertyId, long transactionId,
                                           List<ChildParcel> children) {
        var parentRows = jdbc.queryForList("""
                SELECT id, token_ref, state_version FROM chain.token
                 WHERE property_id = :propertyId AND status = 'ACTIVE'
                """, new MapSqlParameterSource("propertyId", parentPropertyId));
        Long parentTokenId = parentRows.isEmpty() ? null : ((Number) parentRows.get(0).get("id")).longValue();

        List<Map<String, Object>> minted = new ArrayList<>();
        for (ChildParcel child : children) {
            String tokenRef = numbering.next(stateCode, "TOKEN_REF", null);
            byte[] propertyRefHash = Hashes.sha256(child.propertyRef());
            byte[] ownerHash = ownerSetHash(child.owners());
            byte[] prevState = parentTokenId == null ? null : previousStateHash(parentTokenId);
            byte[] state = stateHash(tokenRef, 1, propertyRefHash, ownerHash, prevState, null);
            var keyHolder = new GeneratedKeyHolder();
            jdbc.update("""
                    INSERT INTO chain.token (state_code, token_ref, property_id, parent_token_id, state_version,
                        status, property_ref_hash, owner_set_hash, prev_state_hash, minted_txn_id)
                    VALUES (:stateCode, :tokenRef, :propertyId, :parentTokenId, 1, 'ACTIVE', :propertyRefHash,
                        :ownerHash, :prevState, :txnId)
                    """, new MapSqlParameterSource()
                    .addValue("stateCode", stateCode)
                    .addValue("tokenRef", tokenRef)
                    .addValue("propertyId", child.propertyId())
                    .addValue("parentTokenId", parentTokenId)
                    .addValue("propertyRefHash", propertyRefHash)
                    .addValue("ownerHash", ownerHash)
                    .addValue("prevState", prevState)
                    .addValue("txnId", transactionId), keyHolder, new String[]{"id"});
            long childTokenId = keyHolder.getKey().longValue();
            jdbc.update("UPDATE core.property SET token_id = :tokenId WHERE id = :propertyId",
                    new MapSqlParameterSource().addValue("tokenId", childTokenId)
                            .addValue("propertyId", child.propertyId()));
            recordHistory(childTokenId, 1, "SPLIT", transactionId, ownerHash, child.owners(), null, prevState, state);
            anchor(stateCode, childTokenId, tokenRef, transactionId, state, "SPLIT");
            minted.add(tokenState(childTokenId, "SPLIT"));
        }

        if (parentTokenId != null) {
            jdbc.update("""
                    UPDATE chain.token SET status = 'SUPERSEDED', superseded_at = now() WHERE id = :id
                    """, new MapSqlParameterSource("id", parentTokenId));
            jdbc.update("UPDATE core.property SET status = 'SUPERSEDED' WHERE id = :propertyId",
                    new MapSqlParameterSource("propertyId", parentPropertyId));
            byte[] prevState = previousStateHash(parentTokenId);
            int version = ((Number) parentRows.get(0).get("state_version")).intValue() + 1;
            byte[] state = stateHash((String) parentRows.get(0).get("token_ref"), version,
                    Hashes.sha256(String.valueOf(parentPropertyId)), prevState == null ? new byte[32] : prevState,
                    prevState, null);
            recordHistory(parentTokenId, version, "SUPERSEDE", transactionId,
                    prevState == null ? new byte[32] : prevState, List.of(), null, prevState, state);
        }
        return minted;
    }

    public Map<String, Object> tokenState(long tokenId, String operation) {
        Map<String, Object> row = jdbc.queryForList("""
                SELECT id, token_ref, state_version, status, property_id, parent_token_id,
                       encode(property_ref_hash,'hex') AS property_ref_hash,
                       encode(owner_set_hash,'hex') AS owner_set_hash,
                       encode(prev_state_hash,'hex') AS prev_state_hash, minted_at
                  FROM chain.token WHERE id = :id
                """, new MapSqlParameterSource("id", tokenId)).get(0);
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("operation", operation);
        return out;
    }

    public List<Map<String, Object>> history(long tokenId) {
        return jdbc.queryForList("""
                SELECT state_version, operation, transaction_id, owner_set_json,
                       encode(owner_set_hash,'hex') AS owner_set_hash,
                       encode(state_hash,'hex') AS state_hash,
                       encode(prev_state_hash,'hex') AS prev_state_hash,
                       onchain_tx_hash, recorded_at
                  FROM chain.token_state_history WHERE token_id = :id ORDER BY state_version
                """, new MapSqlParameterSource("id", tokenId));
    }

    private byte[] previousStateHash(long tokenId) {
        return jdbc.query("""
                SELECT state_hash FROM chain.token_state_history
                 WHERE token_id = :id ORDER BY state_version DESC LIMIT 1
                """, new MapSqlParameterSource("id", tokenId), rs -> rs.next() ? rs.getBytes(1) : null);
    }

    private void recordHistory(long tokenId, int version, String operation, long transactionId, byte[] ownerHash,
                               List<Owner> owners, byte[] evidenceRoot, byte[] prevState, byte[] state) {
        jdbc.update("""
                INSERT INTO chain.token_state_history (token_id, state_version, operation, transaction_id,
                    owner_set_hash, owner_set_json, evidence_root, prev_state_hash, state_hash)
                VALUES (:tokenId, :version, :operation, :txnId, :ownerHash, cast(:ownerJson AS jsonb),
                    :evidenceRoot, :prevState, :stateHash)
                """, new MapSqlParameterSource()
                .addValue("tokenId", tokenId)
                .addValue("version", version)
                .addValue("operation", operation)
                .addValue("txnId", transactionId)
                .addValue("ownerHash", ownerHash)
                .addValue("ownerJson", json(owners))
                .addValue("evidenceRoot", evidenceRoot)
                .addValue("prevState", prevState)
                .addValue("stateHash", state));
    }

    private void anchor(String stateCode, long tokenId, String tokenRef, long transactionId, byte[] stateHash,
                        String operation) {
        String txHash = chain.anchor(new ChainService.AnchorRequest(stateCode, "PROPERTY_TOKEN", "recordTokenState",
                Map.of("tokenRef", tokenRef, "operation", operation, "stateHash", Hashes.hex(stateHash)),
                transactionId, tokenId, null, stateHash, tokenRef));
        if (txHash != null) {
            jdbc.update("""
                    UPDATE chain.token_state_history SET onchain_tx_hash = :txHash
                     WHERE token_id = :tokenId AND state_version = (
                        SELECT max(state_version) FROM chain.token_state_history WHERE token_id = :tokenId)
                    """, new MapSqlParameterSource().addValue("tokenId", tokenId).addValue("txHash", txHash));
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise owner set", e);
        }
    }
}
