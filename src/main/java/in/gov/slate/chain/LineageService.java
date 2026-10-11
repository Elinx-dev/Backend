package in.gov.slate.chain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.JurisdictionGuard;

/**
 * Parent/child lineage of a property, looked up by token ref, property ref or numeric
 * property ID. Returns every ancestor and descendant whatever their status (an INACTIVE
 * parent still lists its children), with each node's token and blockchain anchor state.
 */
@Service
public class LineageService {

    static final Pattern REF = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,39}$");
    static final int MAX_DEPTH = 20;

    static final String LINEAGE_SQL = """
            WITH RECURSIVE up AS (
                SELECT id, parent_property_id, 0 AS depth FROM core.property WHERE id = :id
                UNION ALL
                SELECT p.id, p.parent_property_id, up.depth - 1
                  FROM core.property p JOIN up ON p.id = up.parent_property_id
                 WHERE up.depth > -%1$d
            ), down AS (
                SELECT id, 0 AS depth FROM core.property WHERE id = :id
                UNION ALL
                SELECT c.id, down.depth + 1
                  FROM core.property c JOIN down ON c.parent_property_id = down.id
                 WHERE down.depth < %1$d
            ), nodes AS (
                SELECT id, depth FROM up UNION SELECT id, depth FROM down
            )
            SELECT n.depth,
                   CASE WHEN n.depth < 0 THEN 'ANCESTOR' WHEN n.depth = 0 THEN 'SELF' ELSE 'DESCENDANT' END
                       AS relation,
                   p.id AS property_id, p.property_ref, p.status AS property_status, p.survey_no,
                   p.subdivision_no, p.extent_value, p.extent_unit, parent.property_ref AS parent_property_ref,
                   t.token_ref, t.status AS token_status, t.approval_status, t.state_version,
                   pt.token_ref AS parent_token_ref, mint_tx.txn_ref AS created_by_txn_ref,
                   (SELECT count(*) FROM chain.token_state_history h WHERE h.token_id = t.id) AS block_count,
                   anchor.status AS anchor_status, anchor.tx_hash AS anchor_tx_hash,
                   anchor.block_number AS anchor_block_number, anchor.error_message AS anchor_note,
                   (SELECT string_agg(o.owner_name, ', ' ORDER BY o.id) FROM core.property_owner o
                     WHERE o.property_id = p.id AND o.effective_to IS NULL) AS current_owners
              FROM nodes n
              JOIN core.property p ON p.id = n.id
              LEFT JOIN core.property parent ON parent.id = p.parent_property_id
              LEFT JOIN chain.token t ON t.id = p.token_id
              LEFT JOIN chain.token pt ON pt.id = t.parent_token_id
              LEFT JOIN core.transaction mint_tx ON mint_tx.id = t.minted_txn_id
              LEFT JOIN LATERAL (
                  SELECT b.status, b.tx_hash, b.block_number, b.error_message
                    FROM chain.blockchain_transaction b WHERE b.token_id = t.id
                   ORDER BY b.id DESC LIMIT 1
              ) anchor ON TRUE
             WHERE p.state_code = :stateCode
             ORDER BY n.depth, p.property_ref
            """.formatted(MAX_DEPTH);

    private final NamedParameterJdbcTemplate jdbc;
    private final JurisdictionGuard guard;
    private final AuditService audit;
    private final boolean chainEnabled;
    private final boolean submitterConfigured;

    public LineageService(NamedParameterJdbcTemplate jdbc, JurisdictionGuard guard, AuditService audit,
                          @Value("${slate.chain.enabled}") boolean chainEnabled,
                          @Value("${slate.chain.submitter-private-key:}") String submitterKey) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.chainEnabled = chainEnabled;
        this.submitterConfigured = submitterKey != null && !submitterKey.isBlank();
    }

    public Map<String, Object> lineage(String tokenRef, String propertyRef, Long propertyId) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("PROPERTY_READ");
        user.requirePermission("TOKEN_READ");
        int given = (blank(tokenRef) ? 0 : 1) + (blank(propertyRef) ? 0 : 1) + (propertyId == null ? 0 : 1);
        if (given != 1) {
            throw ApiException.badRequest("Give exactly one of tokenRef, propertyRef or propertyId");
        }
        String lookupBy;
        String where;
        MapSqlParameterSource params = new MapSqlParameterSource("stateCode", user.stateCode());
        if (!blank(tokenRef)) {
            lookupBy = "tokenRef";
            where = "p.id = (SELECT t.property_id FROM chain.token t WHERE t.token_ref = :value)";
            params.addValue("value", validRef(tokenRef, "tokenRef"));
        } else if (!blank(propertyRef)) {
            lookupBy = "propertyRef";
            where = "p.property_ref = :value";
            params.addValue("value", validRef(propertyRef, "propertyRef"));
        } else {
            if (propertyId <= 0) {
                throw ApiException.badRequest("propertyId must be a positive number");
            }
            lookupBy = "propertyId";
            where = "p.id = :value";
            params.addValue("value", propertyId);
        }
        var rows = jdbc.queryForList("""
                SELECT p.id, p.property_ref, p.state_code, p.district_code, p.taluk_code, p.village_code, p.sro_code
                  FROM core.property p WHERE %s AND p.state_code = :stateCode
                """.formatted(where), params);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Property");
        }
        Map<String, Object> property = rows.get(0);
        guard.require(user, property, "Property");
        long id = ((Number) property.get("id")).longValue();

        List<Map<String, Object>> nodes = jdbc.queryForList(LINEAGE_SQL,
                new MapSqlParameterSource("id", id).addValue("stateCode", user.stateCode()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lookupBy", lookupBy);
        out.put("property", nodes.stream().filter(n -> "SELF".equals(n.get("relation"))).findFirst().orElse(null));
        out.put("ancestors", nodes.stream().filter(n -> "ANCESTOR".equals(n.get("relation"))).toList());
        out.put("descendants", nodes.stream().filter(n -> "DESCENDANT".equals(n.get("relation"))).toList());
        out.put("nodes", nodes);
        out.put("chain", chainStatus(nodes));

        audit.record("LINEAGE_VIEWED", "PROPERTY", String.valueOf(id), null, (String) property.get("property_ref"),
                Map.of("lookupBy", lookupBy, "nodeCount", nodes.size()), null);
        return out;
    }

    private Map<String, Object> chainStatus(List<Map<String, Object>> nodes) {
        Boolean contract = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cfg.chain_contract WHERE active)", Map.of(), Boolean.class);
        boolean connected = chainEnabled && submitterConfigured && Boolean.TRUE.equals(contract);
        Map<String, Long> anchors = new LinkedHashMap<>();
        nodes.stream().map(n -> n.get("anchor_status")).filter(java.util.Objects::nonNull)
                .forEach(s -> anchors.merge(s.toString(), 1L, Long::sum));
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("connected", connected);
        chain.put("source", connected ? "BLOCKCHAIN_ANCHORED" : "SLATE_CHAIN_RECORDS");
        chain.put("anchorStatusCounts", anchors);
        chain.put("note", connected
                ? "Each token state is anchored on the configured chain; anchor_tx_hash identifies the block entry."
                : "No blockchain node or contract is configured. Lineage comes from SLATE's chain records "
                  + "(tokens, parent tokens and blocks); anchors are SKIPPED until a chain is connected.");
        return chain;
    }

    private static String validRef(String value, String name) {
        String ref = value.trim().toUpperCase();
        if (!REF.matcher(ref).matches()) {
            throw ApiException.badRequest(name + " has an invalid format");
        }
        return ref;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
