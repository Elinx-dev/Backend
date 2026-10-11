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

    /** An officer action written as a block on each child token of a subdivision transaction. */
    public record ChainEvent(String actionCode, String actorRole, Long actorUserId, String decision,
                             String approvalStatus, String remarks) {
    }

    /** Every block a subdivision transaction wrote: the parent's INACTIVE close and each child's versions. */
    public static final String TRANSACTION_BLOCKS_SQL = """
            SELECT relation, property_ref, token_ref, block, operation, action_code, actor_role, decision,
                   approval_status, remarks, state_hash, anchor_status, recorded_at
              FROM (SELECT CASE WHEN tp.id IS NULL THEN 'PARENT' ELSE 'CHILD' END AS relation, tp.seq,
                           p.property_ref, t.token_ref, h.state_version AS block, h.operation, h.action_code,
                           h.actor_role, h.decision, h.approval_status, h.remarks,
                           encode(h.state_hash, 'hex') AS state_hash,
                           (SELECT b.status FROM chain.blockchain_transaction b
                             WHERE b.token_id = t.id AND b.args_json ->> 'stateHash' = '0x' || encode(h.state_hash, 'hex')
                             ORDER BY b.id DESC LIMIT 1) AS anchor_status,
                           h.recorded_at
                      FROM chain.token_state_history h
                      JOIN chain.token t ON t.id = h.token_id
                      JOIN core.property p ON p.id = t.property_id
                      LEFT JOIN core.transaction_property tp ON tp.property_id = p.id AND tp.transaction_id = :txnId
                     WHERE h.transaction_id = :txnId
                       AND EXISTS (SELECT 1 FROM core.transaction_property x WHERE x.transaction_id = :txnId)) blocks
             ORDER BY CASE relation WHEN 'PARENT' THEN 0 ELSE 1 END, seq, block
            """;

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
        return split(stateCode, parentPropertyId, transactionId, children, null);
    }

    /**
     * Subdivision at Register. The parent gets a token first if it never had one, so every child token points
     * to it. The parent token is closed as INACTIVE and that final state is anchored; each child token starts
     * at version 1 (SPLIT) with the event's approval status.
     */
    @Transactional
    public List<Map<String, Object>> subdivide(String stateCode, long parentPropertyId, String parentPropertyRef,
                                               long transactionId, List<ChildParcel> children, ChainEvent event) {
        Boolean hasToken = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chain.token WHERE property_id = :id AND status = 'ACTIVE')
                """, new MapSqlParameterSource("id", parentPropertyId), Boolean.class);
        if (!Boolean.TRUE.equals(hasToken)) {
            mintOrUpdate(stateCode, parentPropertyId, parentPropertyRef, transactionId,
                    currentOwners(parentPropertyId), null);
        }
        return split(stateCode, parentPropertyId, transactionId, children, event);
    }

    private List<Map<String, Object>> split(String stateCode, long parentPropertyId, long transactionId,
                                            List<ChildParcel> children, ChainEvent event) {
        boolean subdivision = event != null;
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
            recordHistory(childTokenId, 1, "SPLIT", transactionId, ownerHash, child.owners(), null, prevState, state,
                    event);
            if (subdivision) {
                jdbc.update("UPDATE chain.token SET approval_status = :status WHERE id = :id",
                        new MapSqlParameterSource().addValue("id", childTokenId)
                                .addValue("status", event.approvalStatus()));
            }
            anchor(stateCode, childTokenId, tokenRef, transactionId, state, "SPLIT");
            minted.add(tokenState(childTokenId, "SPLIT"));
        }

        if (parentTokenId != null) {
            String parentPropertyRef = jdbc.queryForObject(
                    "SELECT property_ref FROM core.property WHERE id = :id",
                    new MapSqlParameterSource("id", parentPropertyId), String.class);
            byte[] prevState = previousStateHash(parentTokenId);
            int version = ((Number) parentRows.get(0).get("state_version")).intValue() + 1;
            // The superseded parcel no longer carries ownership: its owners now sit on the child parcels.
            byte[] emptyOwnerHash = ownerSetHash(List.of());
            byte[] state = stateHash((String) parentRows.get(0).get("token_ref"), version,
                    Hashes.sha256(parentPropertyRef), emptyOwnerHash, prevState, null);
            jdbc.update("""
                    UPDATE chain.token SET status = :status, superseded_at = now(), state_version = :version,
                           owner_set_hash = :ownerHash, prev_state_hash = :prevState,
                           approval_status = COALESCE(:approvalStatus, approval_status)
                     WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", parentTokenId)
                    .addValue("status", subdivision ? "INACTIVE" : "SUPERSEDED")
                    .addValue("approvalStatus", subdivision ? "INACTIVE" : null)
                    .addValue("version", version)
                    .addValue("ownerHash", emptyOwnerHash)
                    .addValue("prevState", prevState));
            ChainEvent parentEvent = subdivision
                    ? new ChainEvent(event.actionCode(), event.actorRole(), event.actorUserId(), null, "INACTIVE",
                            "Subdivided into " + String.join(", ",
                                    children.stream().map(ChildParcel::propertyRef).toList()))
                    : null;
            recordHistory(parentTokenId, version, "SUPERSEDE", transactionId,
                    emptyOwnerHash, List.of(), null, prevState, state, parentEvent);
            if (subdivision) {
                anchor(stateCode, parentTokenId, (String) parentRows.get(0).get("token_ref"), transactionId, state,
                        "SUPERSEDE");
            }
        }
        jdbc.update("UPDATE core.property SET status = 'INACTIVE' WHERE id = :propertyId",
                new MapSqlParameterSource("propertyId", parentPropertyId));
        jdbc.update("""
                UPDATE core.property_owner SET effective_to = current_date
                 WHERE property_id = :propertyId AND effective_to IS NULL
                """, new MapSqlParameterSource("propertyId", parentPropertyId));
        return minted;
    }

    /**
     * Adds one block (ANNOTATE version) to every child token created by this subdivision transaction, recording
     * the officer action and the child's new approval status. Owners are unchanged; the block's evidence covers
     * the action and the child's current survey details. A transaction without child properties gets nothing.
     */
    @Transactional
    public List<Map<String, Object>> annotateChildren(String stateCode, long transactionId, ChainEvent event) {
        var children = jdbc.queryForList("""
                SELECT t.id, t.token_ref, t.state_version, t.property_ref_hash, p.id AS property_id,
                       concat_ws('|', p.property_ref, p.survey_no, p.subdivision_no, p.extent_value, p.extent_unit,
                                 p.boundary_north, p.boundary_south, p.boundary_east, p.boundary_west) AS snapshot
                  FROM core.transaction_property tp
                  JOIN core.property p ON p.id = tp.property_id
                  JOIN chain.token t ON t.id = p.token_id
                 WHERE tp.transaction_id = :txnId
                 ORDER BY tp.seq
                """, new MapSqlParameterSource("txnId", transactionId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> child : children) {
            long tokenId = ((Number) child.get("id")).longValue();
            String tokenRef = (String) child.get("token_ref");
            int version = ((Number) child.get("state_version")).intValue() + 1;
            List<Owner> owners = currentOwners(((Number) child.get("property_id")).longValue());
            byte[] ownerHash = ownerSetHash(owners);
            byte[] evidence = Hashes.sha256(String.join("|", event.actionCode(), String.valueOf(event.actorRole()),
                    String.valueOf(event.actorUserId()), String.valueOf(event.decision()),
                    String.valueOf(event.approvalStatus()), String.valueOf(event.remarks()),
                    String.valueOf(child.get("snapshot"))));
            byte[] prevState = previousStateHash(tokenId);
            byte[] state = stateHash(tokenRef, version, (byte[]) child.get("property_ref_hash"), ownerHash, prevState,
                    evidence);
            jdbc.update("""
                    UPDATE chain.token SET state_version = :version, owner_set_hash = :ownerHash,
                           evidence_root = :evidence, prev_state_hash = :prevState, approval_status = :status
                     WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", tokenId)
                    .addValue("version", version)
                    .addValue("ownerHash", ownerHash)
                    .addValue("evidence", evidence)
                    .addValue("prevState", prevState)
                    .addValue("status", event.approvalStatus()));
            recordHistory(tokenId, version, "ANNOTATE", transactionId, ownerHash, owners, evidence, prevState, state,
                    event);
            anchor(stateCode, tokenId, tokenRef, transactionId, state, "ANNOTATE");
            out.add(tokenState(tokenId, "ANNOTATE"));
        }
        return out;
    }

    private List<Owner> currentOwners(long propertyId) {
        return jdbc.query("""
                SELECT owner_name, share_pct FROM core.property_owner
                 WHERE property_id = :id AND effective_to IS NULL ORDER BY id
                """, new MapSqlParameterSource("id", propertyId),
                (rs, i) -> new Owner(rs.getString("owner_name"), rs.getBigDecimal("share_pct")));
    }

    public Map<String, Object> tokenState(long tokenId, String operation) {
        Map<String, Object> row = jdbc.queryForList("""
                SELECT id, token_ref, state_version, status, approval_status, property_id, parent_token_id,
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
                SELECT state_version, operation, transaction_id, action_code, actor_role, decision,
                       approval_status, remarks, owner_set_json,
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
        recordHistory(tokenId, version, operation, transactionId, ownerHash, owners, evidenceRoot, prevState, state,
                null);
    }

    private void recordHistory(long tokenId, int version, String operation, long transactionId, byte[] ownerHash,
                               List<Owner> owners, byte[] evidenceRoot, byte[] prevState, byte[] state,
                               ChainEvent event) {
        jdbc.update("""
                INSERT INTO chain.token_state_history (token_id, state_version, operation, transaction_id,
                    owner_set_hash, owner_set_json, evidence_root, prev_state_hash, state_hash,
                    action_code, actor_role, actor_user_id, decision, approval_status, remarks)
                VALUES (:tokenId, :version, :operation, :txnId, :ownerHash, cast(:ownerJson AS jsonb),
                    :evidenceRoot, :prevState, :stateHash, :actionCode, :actorRole, :actorUserId, :decision,
                    :approvalStatus, :remarks)
                """, new MapSqlParameterSource()
                .addValue("actionCode", event == null ? null : event.actionCode())
                .addValue("actorRole", event == null ? null : event.actorRole())
                .addValue("actorUserId", event == null ? null : event.actorUserId())
                .addValue("decision", event == null ? null : event.decision())
                .addValue("approvalStatus", event == null ? null : event.approvalStatus())
                .addValue("remarks", event == null ? null : event.remarks())
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
        chain.anchor(new ChainService.AnchorRequest(stateCode, "PROPERTY_TOKEN", "recordTokenState",
                Map.of("tokenRef", tokenRef, "operation", operation, "stateHash", Hashes.hex(stateHash)),
                transactionId, tokenId, null, stateHash, tokenRef));
        // chain.token_state_history is append-only (V11): the tx hash stays on chain.blockchain_transaction.
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise owner set", e);
        }
    }
}
