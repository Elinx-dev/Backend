package in.gov.slate.chain;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.chain.FabricLedger.Org;

/**
 * Ledger anchoring and read-back on the SLATE Hyperledger Fabric network (chaincode slateland).
 * {@link #anchor} only queues the entry inside the caller's DB transaction, so Register never waits on
 * the ledger. {@link #processQueue} submits queued entries as the actor's organisation (with retries)
 * and records MINED with the block number once the transaction is committed. A chaincode rule
 * rejection (double sale, active encumbrance, wrong organisation) is REJECTED and not retried. When the
 * ledger is switched off or its identities are missing, the entry is SKIPPED and the registry keeps working.
 */
@Service
public class ChainService {

    private static final Logger log = LoggerFactory.getLogger(ChainService.class);
    static final String CONTRACT = "PROPERTY_TOKEN";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FabricLedger ledger;
    private final boolean enabled;
    private final int maxAttempts;

    public ChainService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, FabricLedger ledger,
                        @Value("${slate.chain.enabled}") boolean enabled,
                        @Value("${slate.chain.max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ledger = ledger;
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
    }

    public record AnchorRequest(String stateCode, String contractCode, String functionName,
                                Map<String, Object> args, Long transactionId, Long tokenId,
                                BigInteger onchainTokenId, byte[] stateHash, String tokenRef) {
    }

    /** Queues the anchor; returns null because the Fabric transaction id is only known after the worker submits it. */
    public String anchor(AnchorRequest request) {
        long rowId = queue(request);
        String reason = skipReason(request.stateCode());
        if (reason != null) {
            markSkipped(rowId, reason);
        }
        return null;
    }

    /** Queues the registered deed's SHA-256 so the certificate QR can be checked against the ledger. */
    public void anchorDeed(String stateCode, long transactionId, String documentNo, byte[] deedHash) {
        String tokenRef = jdbc.query("""
                SELECT t.token_ref FROM core.transaction tx JOIN core.property p ON p.id = tx.property_id
                  JOIN chain.token t ON t.id = p.token_id WHERE tx.id = :id
                """, new MapSqlParameterSource("id", transactionId), rs -> rs.next() ? rs.getString(1) : "");
        anchor(new AnchorRequest(stateCode, CONTRACT, "recordDeed",
                Map.of("documentNo", documentNo, "deedHash", in.gov.slate.common.Hashes.hex(deedHash),
                        "tokenRef", tokenRef == null ? "" : tokenRef, "role", "REGISTRATION_OFFICER"),
                transactionId, null, null, deedHash, documentNo));
    }

    public boolean connected(String stateCode) {
        return skipReason(stateCode) == null;
    }

    String skipReason(String stateCode) {
        if (!enabled) {
            return "Ledger anchoring is disabled (slate.chain.enabled=false)";
        }
        return ledger.missingCrypto();
    }

    public FabricLedger ledger() {
        return ledger;
    }

    @Scheduled(fixedDelayString = "${slate.chain.worker-delay-ms:5000}", initialDelayString = "${slate.chain.worker-delay-ms:5000}")
    public void processQueue() {
        if (skipReason(null) != null) {
            return;
        }
        var submitted = jdbc.queryForList("""
                SELECT id, tx_hash FROM chain.blockchain_transaction
                 WHERE status = 'SUBMITTED' ORDER BY id LIMIT 50
                """, Map.of());
        for (Map<String, Object> row : submitted) {
            confirm(((Number) row.get("id")).longValue(), (String) row.get("tx_hash"));
        }
        var due = jdbc.queryForList("""
                SELECT id, state_code, function_name, args_json::text AS args, attempts
                  FROM chain.blockchain_transaction
                 WHERE (status = 'QUEUED' OR (status = 'FAILED' AND attempts < :max))
                   AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                 ORDER BY id LIMIT 20
                """, new MapSqlParameterSource("max", maxAttempts));
        for (Map<String, Object> row : due) {
            if (!send(row)) {
                return;
            }
        }
    }

    /** Submits one entry; false when the ledger is unreachable, so later entries keep their order. */
    private boolean send(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        int claimed = jdbc.update("""
                UPDATE chain.blockchain_transaction SET attempts = attempts + 1
                 WHERE id = :id AND attempts = :attempts AND status IN ('QUEUED', 'FAILED')
                """, new MapSqlParameterSource().addValue("id", id).addValue("attempts", attempts));
        if (claimed == 0) {
            return true;
        }
        Call call = null;
        try {
            Map<String, Object> args = mapper.readValue((String) row.get("args"), new TypeReference<>() { });
            call = toCall((String) row.get("function_name"), args);
            jdbc.update("""
                    UPDATE chain.blockchain_transaction SET org_msp = :org, chaincode_function = :fn WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", id).addValue("org", call.org().mspId())
                    .addValue("fn", call.function()));
            FabricLedger.Submitted result;
            try {
                result = ledger.submit(call.org(), call.function(), call.args());
            } catch (FabricLedger.ChaincodeRejected e) {
                Call fallback = lateMint(call, args, e.getMessage());
                if (fallback == null) {
                    throw e;
                }
                call = fallback;
                result = ledger.submit(call.org(), call.function(), call.args());
            }
            markMined(id, result, call);
            return true;
        } catch (FabricLedger.ChaincodeRejected e) {
            log.info("Ledger rejected anchor {}: {}", id, e.getMessage());
            markRejected(id, e.getMessage());
            return true;
        } catch (Exception e) {
            log.warn("Ledger anchor {} failed: {}", id, e.getMessage());
            markFailed(id, attempts + 1, e.getMessage());
            return false;
        }
    }

    record Call(Org org, String function, String[] args) {
    }

    /** Maps a queued SLATE anchor to its slateland chaincode transaction. */
    Call toCall(String functionName, Map<String, Object> a) {
        Org org = a.get("org") != null ? Org.forMsp(s(a.get("org"))) : Org.forRole(s(a.get("role")));
        String version = s(a.get("version"));
        String prev = version.isEmpty() ? "" : String.valueOf(Integer.parseInt(version) - 1);
        return switch (functionName) {
            case "recordDeed" -> new Call(Org.REGISTRATION, "RecordDeed",
                    new String[]{s(a.get("documentNo")), s(a.get("deedHash")), s(a.get("tokenRef"))});
            case "addEncumbrance" -> new Call(org, "AddEncumbrance", new String[]{s(a.get("tokenRef")),
                    s(a.get("encRef")), s(a.get("type")), s(a.get("holderHash")), s(a.get("docHash"))});
            case "releaseEncumbrance" -> new Call(org, "ReleaseEncumbrance",
                    new String[]{s(a.get("tokenRef")), s(a.get("encRef")), s(a.get("docHash"))});
            case "recordTokenState" -> switch (s(a.get("operation"))) {
                case "MINT", "SPLIT" -> new Call(org == Org.SURVEY ? Org.SURVEY : Org.REGISTRATION, "MintParcel",
                        mintArgs(a));
                case "UPDATE" -> new Call(Org.REGISTRATION, "TransferParcel", new String[]{s(a.get("tokenRef")), prev,
                        s(a.get("stateHash")), s(a.get("metadataHash")), s(a.get("geoHash")), json(a.get("shares")),
                        s(a.get("deedNo"))});
                case "SUPERSEDE" -> new Call(org == Org.SURVEY ? Org.SURVEY : Org.REGISTRATION, "RetireParcel",
                        new String[]{s(a.get("tokenRef")), prev, s(a.get("stateHash")), s(a.get("status")),
                                json(a.get("children")), s(a.get("role"))});
                case "ANNOTATE" -> new Call(org, "Annotate", new String[]{s(a.get("tokenRef")), prev,
                        s(a.get("stateHash")), s(a.get("action")), s(a.get("role")), s(a.get("decision"))});
                default -> throw new IllegalStateException("Unknown token operation " + a.get("operation"));
            };
            default -> throw new IllegalStateException("Unknown chain function " + functionName);
        };
    }

    private String[] mintArgs(Map<String, Object> a) {
        return new String[]{s(a.get("tokenRef")), s(a.get("version")).isEmpty() ? "1" : s(a.get("version")),
                s(a.get("propertyRefHash")), s(a.get("stateHash")), s(a.get("metadataHash")), s(a.get("geoHash")),
                json(a.get("shares")), s(a.get("parentTokenRef"))};
    }

    /** A token minted while no ledger was connected is minted on its first later transfer. */
    private Call lateMint(Call call, Map<String, Object> a, String error) {
        if (!"TransferParcel".equals(call.function()) || !error.contains("NOT_FOUND")) {
            return null;
        }
        return new Call(Org.REGISTRATION, "MintParcel", mintArgs(a));
    }

    private void confirm(long id, String txId) {
        long block = txId == null ? -1 : ledger.blockOf(txId);
        if (block >= 0) {
            jdbc.update("""
                    UPDATE chain.blockchain_transaction SET status = 'MINED', block_number = :block, mined_at = now(),
                           error_message = NULL WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", id).addValue("block", block));
        }
    }

    /** True/false from the ledger, or null when the ledger is not connected or not reachable. */
    public Boolean hasState(String stateCode, String tokenRef, String stateHashHex) {
        String out = read(Org.REGISTRATION, "HasState", tokenRef, stateHashHex);
        return out == null ? null : Boolean.parseBoolean(out.trim());
    }

    /** The deed hash recorded on the ledger (0x-hex), "" when none is recorded, or null when not connected. */
    public String deedOf(String stateCode, String documentNo) {
        return read(Org.REGISTRATION, "DeedOf", documentNo);
    }

    /** Evaluates a read-only chaincode query; null when not connected, unreachable or not found. */
    public String read(Org org, String function, String... args) {
        if (!connected(null)) {
            return null;
        }
        try {
            return ledger.evaluate(org, function, args);
        } catch (Exception e) {
            log.debug("Ledger read {} failed: {}", function, e.getMessage());
            return null;
        }
    }

    private long queue(AnchorRequest request) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO chain.blockchain_transaction (state_code, network_code, contract_code, function_name,
                    args_json, idempotency_key, status, transaction_id, token_id)
                VALUES (:stateCode, :network, :contract, :function, cast(:args AS jsonb), :key, 'QUEUED',
                    :txnId, :tokenId)
                """, new MapSqlParameterSource()
                .addValue("stateCode", request.stateCode())
                .addValue("network", "FABRIC_" + ledger.channel().toUpperCase())
                .addValue("contract", request.contractCode())
                .addValue("function", request.functionName())
                .addValue("args", json(request.args()))
                .addValue("key", UUID.randomUUID())
                .addValue("txnId", request.transactionId())
                .addValue("tokenId", request.tokenId()), keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    private void markSkipped(long id, String reason) {
        jdbc.update("UPDATE chain.blockchain_transaction SET status = 'SKIPPED', error_message = :reason WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("reason", reason));
    }

    private void markFailed(long id, int attempts, String reason) {
        jdbc.update("""
                UPDATE chain.blockchain_transaction SET status = 'FAILED', error_message = :reason,
                       next_attempt_at = now() + make_interval(secs => :delay) WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("reason", reason)
                .addValue("delay", 30 * attempts));
    }

    private void markRejected(long id, String reason) {
        jdbc.update("""
                UPDATE chain.blockchain_transaction SET status = 'REJECTED', error_message = :reason WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("reason", reason));
    }

    private void markMined(long id, FabricLedger.Submitted result, Call call) {
        jdbc.update("""
                UPDATE chain.blockchain_transaction SET status = 'MINED', tx_hash = :txId, block_number = :block,
                       submitted_at = COALESCE(submitted_at, now()), mined_at = now(), error_message = NULL,
                       validation_code = :code, org_msp = :org, chaincode_function = :fn
                 WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("txId", result.txId())
                .addValue("block", result.blockNumber()).addValue("code", result.validationCode())
                .addValue("org", call.org().mspId()).addValue("fn", call.function()));
    }

    private static String s(Object value) {
        return value == null ? "" : value.toString();
    }

    String json(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise chain args", e);
        }
    }
}
