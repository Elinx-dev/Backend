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

import in.gov.slate.chain.FabricLedger.Org;
import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.Hashes;

/**
 * Tokenisation features on top of the Fabric ledger: encumbrance register, pre-transfer rule check
 * (ledger state + encumbrances + mock court/tax rules), the ledger view of one parcel NFT, the fraud
 * demos and tokenisation metrics.
 */
@Service
public class TokenLedgerService {

    private static final List<String> OFFICER_ROLES = List.of("SRO", "REGISTRATION_OFFICER", "TAHSILDAR", "VAO",
            "SURVEYOR");

    private final NamedParameterJdbcTemplate jdbc;
    private final ChainService chain;
    private final TokenRuleConnector rules;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public TokenLedgerService(NamedParameterJdbcTemplate jdbc, ChainService chain, TokenRuleConnector rules,
                              ObjectMapper mapper, AuditService audit) {
        this.jdbc = jdbc;
        this.chain = chain;
        this.rules = rules;
        this.mapper = mapper;
        this.audit = audit;
    }

    public record EncumbranceRequest(String type, String holderName, String documentRef, BigDecimal amount,
                                     String remarks) {
    }

    public record ReleaseRequest(String releaseDocumentRef, String remarks) {
    }

    // ---- rule check

    /** Pre-transfer checks for the property's active token. Mock rules stand in for court and revenue systems. */
    public Map<String, Object> transferCheck(long propertyId) {
        var rows = jdbc.queryForList("""
                SELECT p.property_ref, t.id AS token_id, t.token_ref, t.state_version
                  FROM core.property p LEFT JOIN chain.token t ON t.id = p.token_id AND t.status = 'ACTIVE'
                 WHERE p.id = :id
                """, new MapSqlParameterSource("id", propertyId));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Property " + propertyId);
        }
        var row = rows.get(0);
        String propertyRef = (String) row.get("property_ref");
        List<Map<String, Object>> checks = new ArrayList<>();
        Long tokenId = row.get("token_id") == null ? null : ((Number) row.get("token_id")).longValue();
        String tokenRef = (String) row.get("token_ref");

        long activeEnc = tokenId == null ? 0 : jdbc.queryForObject("""
                SELECT count(*) FROM chain.encumbrance WHERE token_id = :id AND status = 'ACTIVE'
                """, new MapSqlParameterSource("id", tokenId), Long.class);
        checks.add(check("ENCUMBRANCE", activeEnc == 0 ? "PASS" : "FAIL", "SLATE",
                activeEnc == 0 ? "No active mortgage, lease or court attachment"
                        : activeEnc + " active encumbrance(s) must be released before transfer"));

        if (tokenRef == null) {
            checks.add(check("LEDGER_STATE", "PASS", "LEDGER", "No token yet: it is minted at registration"));
        } else {
            int version = ((Number) row.get("state_version")).intValue();
            String raw = chain.read(Org.REGISTRATION, "CanTransfer", tokenRef, String.valueOf(version));
            if (raw == null) {
                checks.add(check("LEDGER_STATE", "SKIPPED", "LEDGER", "Fabric ledger not connected"));
            } else {
                Map<String, Object> ledger = parse(raw);
                boolean allowed = Boolean.TRUE.equals(ledger.get("allowed"));
                checks.add(check("LEDGER_STATE", allowed ? "PASS" : "FAIL", "LEDGER", allowed
                        ? (Boolean.TRUE.equals(ledger.get("onLedger")) ? "Ledger version " + version
                                + " matches; parcel is ACTIVE" : "Parcel not on the ledger yet; minted on transfer")
                        : String.join("; ", castList(ledger.get("reasons")))));
            }
        }

        var facts = rules.facts(propertyRef);
        checks.add(check("COURT_CASE", facts.courtCase() ? "FAIL" : "PASS", facts.source(), facts.courtCase()
                ? "Pending court case " + (facts.courtCaseRef() == null ? "" : facts.courtCaseRef())
                : "No pending court case"));
        boolean dues = facts.taxDues() != null && facts.taxDues().signum() > 0;
        checks.add(check("TAX_DUES", dues ? "FAIL" : "PASS", facts.source(), dues
                ? "Land revenue dues of Rs " + facts.taxDues().toPlainString() + " are unpaid" : "No land revenue dues"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("propertyRef", propertyRef);
        out.put("tokenRef", tokenRef);
        out.put("allowed", checks.stream().noneMatch(c -> "FAIL".equals(c.get("status"))));
        out.put("checks", checks);
        return out;
    }

    /** Blocks Register when a rule check fails, before any ownership change is written. */
    public void requireTransferAllowed(long propertyId) {
        var result = transferCheck(propertyId);
        if (!Boolean.TRUE.equals(result.get("allowed"))) {
            List<String> failed = castMaps(result.get("checks")).stream()
                    .filter(c -> "FAIL".equals(c.get("status")))
                    .map(c -> c.get("code") + ": " + c.get("message")).toList();
            throw ApiException.conflict("Transfer blocked by rule check. " + String.join("; ", failed));
        }
    }

    public Map<String, Object> transferCheckForToken(long tokenId) {
        Long propertyId = jdbc.queryForObject("SELECT property_id FROM chain.token WHERE id = :id",
                new MapSqlParameterSource("id", tokenId), Long.class);
        return transferCheck(propertyId);
    }

    // ---- encumbrances

    public List<Map<String, Object>> encumbrances(long tokenId) {
        return jdbc.queryForList("""
                SELECT e.enc_ref, e.enc_type, e.holder_name, e.document_ref, e.amount, e.remarks, e.status,
                       e.created_role, e.org_msp, e.created_at, e.released_at, e.release_document_ref,
                       (SELECT b.status FROM chain.blockchain_transaction b
                         WHERE b.function_name = 'addEncumbrance' AND b.args_json->>'encRef' = e.enc_ref
                         ORDER BY b.id DESC LIMIT 1) AS ledger_status,
                       (SELECT b.tx_hash FROM chain.blockchain_transaction b
                         WHERE b.function_name = 'addEncumbrance' AND b.args_json->>'encRef' = e.enc_ref
                         ORDER BY b.id DESC LIMIT 1) AS ledger_tx_id,
                       (SELECT b.status FROM chain.blockchain_transaction b
                         WHERE b.function_name = 'releaseEncumbrance' AND b.args_json->>'encRef' = e.enc_ref
                         ORDER BY b.id DESC LIMIT 1) AS release_ledger_status
                  FROM chain.encumbrance e WHERE e.token_id = :id ORDER BY e.id DESC
                """, new MapSqlParameterSource("id", tokenId));
    }

    @Transactional
    public Map<String, Object> addEncumbrance(Map<String, Object> token, EncumbranceRequest req, CurrentUser user) {
        String type = req.type() == null ? "" : req.type().trim().toUpperCase();
        if (!List.of("MORTGAGE", "LEASE", "COURT_ATTACHMENT").contains(type)) {
            throw ApiException.badRequest("type must be MORTGAGE, LEASE or COURT_ATTACHMENT");
        }
        if (blank(req.holderName()) || blank(req.documentRef())) {
            throw ApiException.badRequest("holderName and documentRef are required");
        }
        if (!"ACTIVE".equals(token.get("status"))) {
            throw ApiException.conflict("Only an ACTIVE token can be encumbered");
        }
        String role = officerRole(user);
        Org org = Org.forRole(role);
        if (org == Org.SURVEY || (org == Org.REVENUE && !"COURT_ATTACHMENT".equals(type))) {
            throw ApiException.forbidden(role + " (" + org.mspId() + ") may not record a " + type);
        }
        long tokenId = ((Number) token.get("id")).longValue();
        String tokenRef = (String) token.get("token_ref");
        long seq = jdbc.queryForObject("SELECT count(*) + 1 FROM chain.encumbrance WHERE token_id = :id",
                new MapSqlParameterSource("id", tokenId), Long.class);
        String encRef = "ENC-" + tokenRef + "-" + seq;
        byte[] holderHash = Hashes.sha256(tokenRef + "|" + req.holderName().trim().toUpperCase());
        byte[] docHash = Hashes.sha256(req.documentRef().trim());
        jdbc.update("""
                INSERT INTO chain.encumbrance (enc_ref, token_id, enc_type, holder_name, holder_hash, document_ref,
                    doc_hash, amount, remarks, created_by, created_role, org_msp)
                VALUES (:encRef, :tokenId, :type, :holder, :holderHash, :doc, :docHash, :amount, :remarks, :userId,
                    :role, :org)
                """, new MapSqlParameterSource().addValue("encRef", encRef).addValue("tokenId", tokenId)
                .addValue("type", type).addValue("holder", req.holderName().trim()).addValue("holderHash", holderHash)
                .addValue("doc", req.documentRef().trim()).addValue("docHash", docHash).addValue("amount", req.amount())
                .addValue("remarks", req.remarks()).addValue("userId", user.id()).addValue("role", role)
                .addValue("org", org.mspId()));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tokenRef", tokenRef);
        args.put("encRef", encRef);
        args.put("type", type);
        args.put("holderHash", Hashes.hex(holderHash));
        args.put("docHash", Hashes.hex(docHash));
        args.put("org", org.mspId());
        chain.anchor(new ChainService.AnchorRequest(stateCode(token), "addEncumbrance", args,
                null, tokenId, null, docHash, tokenRef));
        audit.record("ENCUMBRANCE_ADDED", "TOKEN", String.valueOf(tokenId), encRef, (String) token.get("property_ref"),
                Map.of("type", type, "org", org.mspId()), null);
        return Map.of("encRef", encRef, "status", "ACTIVE", "orgMsp", org.mspId());
    }

    @Transactional
    public Map<String, Object> releaseEncumbrance(Map<String, Object> token, String encRef, ReleaseRequest req,
                                                  CurrentUser user) {
        long tokenId = ((Number) token.get("id")).longValue();
        var rows = jdbc.queryForList("""
                SELECT id, status, org_msp, enc_type FROM chain.encumbrance WHERE enc_ref = :encRef AND token_id = :tokenId
                """, new MapSqlParameterSource().addValue("encRef", encRef).addValue("tokenId", tokenId));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Encumbrance " + encRef);
        }
        if (!"ACTIVE".equals(rows.get(0).get("status"))) {
            throw ApiException.conflict("Encumbrance " + encRef + " is already released");
        }
        if (req == null || blank(req.releaseDocumentRef())) {
            throw ApiException.badRequest("releaseDocumentRef is required");
        }
        String role = officerRole(user);
        Org org = Org.forRole(role);
        if (org != Org.REGISTRATION && !org.mspId().equals(rows.get(0).get("org_msp"))) {
            throw ApiException.forbidden(org.mspId() + " may not release an encumbrance recorded by "
                    + rows.get(0).get("org_msp"));
        }
        byte[] docHash = Hashes.sha256(req.releaseDocumentRef().trim());
        jdbc.update("""
                UPDATE chain.encumbrance SET status = 'RELEASED', released_by = :userId, released_at = now(),
                       release_document_ref = :doc, remarks = COALESCE(:remarks, remarks) WHERE enc_ref = :encRef
                """, new MapSqlParameterSource().addValue("userId", user.id()).addValue("encRef", encRef)
                .addValue("doc", req.releaseDocumentRef().trim()).addValue("remarks", req.remarks()));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("tokenRef", token.get("token_ref"));
        args.put("encRef", encRef);
        args.put("docHash", Hashes.hex(docHash));
        args.put("org", org.mspId());
        chain.anchor(new ChainService.AnchorRequest(stateCode(token), "releaseEncumbrance",
                args, null, tokenId, null, docHash, (String) token.get("token_ref")));
        audit.record("ENCUMBRANCE_RELEASED", "TOKEN", String.valueOf(tokenId), encRef,
                (String) token.get("property_ref"), Map.of("org", org.mspId()), null);
        return Map.of("encRef", encRef, "status", "RELEASED", "orgMsp", org.mspId());
    }

    // ---- ledger view

    /** The parcel NFT as SLATE holds it next to what the Fabric ledger holds, with the integrity comparison. */
    public Map<String, Object> ledgerView(Map<String, Object> token) {
        long tokenId = ((Number) token.get("id")).longValue();
        String tokenRef = (String) token.get("token_ref");
        Map<String, Object> out = new LinkedHashMap<>();
        boolean connected = chain.connected(null);
        out.put("connected", connected);
        out.put("channel", chain.ledger().channel());
        out.put("chaincode", chain.ledger().chaincode());
        out.put("blockHeight", connected ? chain.ledger().height() : null);

        var metaRows = jdbc.queryForList("""
                SELECT state_version, metadata_json::text AS metadata, '0x' || encode(metadata_hash, 'hex') AS metadata_hash,
                       '0x' || encode(geo_hash, 'hex') AS geo_hash, created_at
                  FROM chain.token_metadata WHERE token_id = :id ORDER BY state_version DESC LIMIT 1
                """, new MapSqlParameterSource("id", tokenId));
        if (!metaRows.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>(metaRows.get(0));
            m.put("metadata", parse((String) m.remove("metadata")));
            out.put("metadata", m);
        }
        String dbStateHash = jdbc.query("""
                SELECT '0x' || encode(state_hash, 'hex') FROM chain.token_state_history
                 WHERE token_id = :id ORDER BY state_version DESC LIMIT 1
                """, new MapSqlParameterSource("id", tokenId), rs -> rs.next() ? rs.getString(1) : null);
        out.put("dbStateVersion", token.get("state_version"));
        out.put("dbStateHash", dbStateHash);
        out.put("owners", jdbc.queryForList("""
                SELECT o.owner_name, o.share_pct FROM core.property_owner o JOIN chain.token t ON t.property_id = o.property_id
                 WHERE t.id = :id AND o.effective_to IS NULL ORDER BY o.id
                """, new MapSqlParameterSource("id", tokenId)).stream().map(o -> {
                    Map<String, Object> r = new LinkedHashMap<>(o);
                    r.put("holder_hash", TokenService.holderHash(tokenRef, (String) o.get("owner_name")));
                    return r;
                }).toList());
        out.put("encumbrances", encumbrances(tokenId));
        out.put("transactions", jdbc.queryForList("""
                SELECT id, function_name, chaincode_function, args_json->>'operation' AS operation, org_msp, status,
                       tx_hash AS tx_id, block_number, validation_code, error_message, submitted_at, mined_at
                  FROM chain.blockchain_transaction
                 WHERE token_id = :id OR (function_name = 'recordDeed' AND args_json->>'tokenRef' = :tokenRef)
                 ORDER BY id DESC
                """, new MapSqlParameterSource().addValue("id", tokenId).addValue("tokenRef", tokenRef)));

        if (connected) {
            String parcel = chain.read(Org.REGISTRATION, "ReadParcel", tokenRef);
            Map<String, Object> onLedger = parcel == null ? null : parse(parcel);
            out.put("parcel", onLedger);
            String encs = chain.read(Org.REGISTRATION, "Encumbrances", tokenRef);
            out.put("ledgerEncumbrances", encs == null ? List.of() : parseList(encs));
            String hist = chain.read(Org.REGISTRATION, "History", tokenRef);
            out.put("ledgerHistory", hist == null ? List.of() : parseList(hist));
            String integrity;
            if (onLedger == null) {
                integrity = "NOT_ON_LEDGER";
            } else if (dbStateHash != null && dbStateHash.equalsIgnoreCase(String.valueOf(onLedger.get("stateHash")))) {
                integrity = "MATCH";
            } else {
                Boolean known = dbStateHash == null ? null : chain.hasState(null, tokenRef, dbStateHash);
                integrity = Boolean.TRUE.equals(known) ? "PENDING" : "MISMATCH";
            }
            out.put("integrity", integrity);
        } else {
            out.put("integrity", "NOT_CONNECTED");
        }
        return out;
    }

    /**
     * Fraud demo: re-sends a transfer of this parcel at an already-used version, as a second sale of the same
     * title would. The chaincode must reject it; nothing changes in SLATE or on the ledger.
     */
    public Map<String, Object> doubleSaleDemo(Map<String, Object> token) {
        String tokenRef = (String) token.get("token_ref");
        if (!chain.connected(null)) {
            throw ApiException.conflict("Fabric ledger is not connected");
        }
        String parcel = chain.read(Org.REGISTRATION, "ReadParcel", tokenRef);
        if (parcel == null) {
            throw ApiException.conflict("Token " + tokenRef + " is not on the ledger yet");
        }
        int ledgerVersion = ((Number) parse(parcel).get("version")).intValue();
        int staleVersion = ledgerVersion - 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tokenRef", tokenRef);
        out.put("ledgerVersion", ledgerVersion);
        out.put("attemptedExpectedVersion", staleVersion);
        try {
            var r = chain.ledger().submit(Org.REGISTRATION, "TransferParcel", tokenRef, String.valueOf(staleVersion),
                    "0xdemo", "0xdemo", "", "[\"DEMO_BUYER\"]", "DEMO-DOUBLE-SALE");
            out.put("rejected", false);
            out.put("txId", r.txId());
        } catch (FabricLedger.ChaincodeRejected e) {
            out.put("rejected", true);
            out.put("reason", e.getMessage());
        }
        jdbc.update("""
                INSERT INTO chain.blockchain_transaction (state_code, network_code, contract_code, function_name,
                    args_json, idempotency_key, status, token_id, org_msp, chaincode_function, error_message)
                VALUES (:stateCode, :network, :contract, 'demoDoubleSale', cast(:args AS jsonb), :key, :status,
                    :tokenId, 'RegistrationMSP', 'TransferParcel', :reason)
                """, new MapSqlParameterSource().addValue("stateCode", stateCode(token))
                .addValue("network", "FABRIC_" + chain.ledger().channel().toUpperCase())
                .addValue("contract", chain.ledger().chaincode())
                .addValue("args", chain.json(Map.of("tokenRef", tokenRef, "expectedVersion", staleVersion)))
                .addValue("key", java.util.UUID.randomUUID())
                .addValue("status", Boolean.TRUE.equals(out.get("rejected")) ? "REJECTED" : "MINED")
                .addValue("tokenId", token.get("id")).addValue("reason", out.get("reason")));
        return out;
    }

    public Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", chain.connected(null));
        out.put("blockHeight", chain.connected(null) ? chain.ledger().height() : null);
        out.put("tokens", jdbc.queryForMap("""
                SELECT count(*) AS total, count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                       count(*) FILTER (WHERE parent_token_id IS NOT NULL) AS child_tokens,
                       count(DISTINCT t.id) FILTER (WHERE (SELECT count(*) FROM core.property_owner o
                            WHERE o.property_id = t.property_id AND o.effective_to IS NULL) > 1) AS fractional
                  FROM chain.token t
                """, Map.of()));
        out.put("ledgerTransactions", jdbc.queryForList("""
                SELECT status, count(*) AS count FROM chain.blockchain_transaction GROUP BY status ORDER BY status
                """, Map.of()));
        out.put("byOrg", jdbc.queryForList("""
                SELECT org_msp, count(*) AS count FROM chain.blockchain_transaction
                 WHERE org_msp IS NOT NULL AND status = 'MINED' GROUP BY org_msp ORDER BY org_msp
                """, Map.of()));
        out.put("rejectedByRule", jdbc.queryForList("""
                SELECT split_part(error_message, ':', 1) AS rule, count(*) AS count FROM chain.blockchain_transaction
                 WHERE status = 'REJECTED' GROUP BY 1 ORDER BY 2 DESC
                """, Map.of()));
        out.put("encumbrances", jdbc.queryForList("""
                SELECT enc_type, status, count(*) AS count FROM chain.encumbrance GROUP BY 1, 2 ORDER BY 1, 2
                """, Map.of()));
        out.put("avgAnchorSeconds", jdbc.queryForObject("""
                SELECT round(avg(extract(epoch FROM (mined_at - submitted_at)))::numeric, 2)
                  FROM chain.blockchain_transaction WHERE status = 'MINED' AND mined_at IS NOT NULL
                """, Map.of(), BigDecimal.class));
        return out;
    }

    // ---- helpers

    static String officerRole(CurrentUser user) {
        return OFFICER_ROLES.stream().filter(user::hasRole).findFirst()
                .orElseThrow(() -> ApiException.forbidden("A Registration, Revenue or Survey officer role is required"));
    }

    private static String stateCode(Map<String, Object> token) {
        return ((String) token.get("state_code")).trim();
    }

    private static Map<String, Object> check(String code, String status, String source, String message) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        c.put("status", status);
        c.put("source", source);
        c.put("message", message);
        return c;
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    private List<Object> parseList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return value == null ? List.of() : ((List<Object>) value).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
