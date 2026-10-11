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

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.utils.Numeric;

/**
 * Blockchain anchoring and read-back on the SLATE Besu network.
 * {@link #anchor} only queues the entry inside the caller's DB transaction, so Register never
 * waits on the chain. {@link #processQueue} sends queued entries (with retries) and records
 * SUBMITTED, then MINED with the block number. When the chain is switched off or has no
 * contract or key, the entry is recorded as SKIPPED and the registry keeps working.
 */
@Service
public class ChainService {

    private static final Logger log = LoggerFactory.getLogger(ChainService.class);
    static final String CONTRACT = "PROPERTY_TOKEN";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String rpcUrl;
    private final long chainId;
    private final String submitterKey;
    private final int maxAttempts;
    private Web3j web3j;

    public ChainService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
                        @Value("${slate.chain.enabled}") boolean enabled,
                        @Value("${slate.chain.rpc-primary}") String rpcUrl,
                        @Value("${slate.chain.chain-id}") long chainId,
                        @Value("${slate.chain.submitter-private-key:}") String submitterKey,
                        @Value("${slate.chain.max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.enabled = enabled;
        this.rpcUrl = rpcUrl;
        this.chainId = chainId;
        this.submitterKey = submitterKey;
        this.maxAttempts = maxAttempts;
    }

    public record AnchorRequest(String stateCode, String contractCode, String functionName,
                                Map<String, Object> args, Long transactionId, Long tokenId,
                                BigInteger onchainTokenId, byte[] stateHash, String tokenRef) {
    }

    /** Queues the anchor; returns null because the transaction hash is only known after the worker sends it. */
    public String anchor(AnchorRequest request) {
        long rowId = queue(request);
        String reason = skipReason(request.stateCode());
        if (reason != null) {
            markSkipped(rowId, reason);
        }
        return null;
    }

    /** Queues the registered deed's SHA-256 so the certificate QR can be checked against the chain. */
    public void anchorDeed(String stateCode, long transactionId, String documentNo, byte[] deedHash) {
        anchor(new AnchorRequest(stateCode, CONTRACT, "recordDeed",
                Map.of("documentNo", documentNo, "deedHash", Numeric.toHexString(deedHash)),
                transactionId, null, null, deedHash, documentNo));
    }

    public boolean connected(String stateCode) {
        return skipReason(stateCode) == null;
    }

    String skipReason(String stateCode) {
        if (!enabled) {
            return "Chain anchoring is disabled (slate.chain.enabled=false)";
        }
        if (contractAddress(stateCode) == null) {
            return "No deployed address configured for contract " + CONTRACT;
        }
        if (submitterKey == null || submitterKey.isBlank()) {
            return "No submitter key is configured";
        }
        return null;
    }

    @Scheduled(fixedDelayString = "${slate.chain.worker-delay-ms:5000}", initialDelayString = "${slate.chain.worker-delay-ms:5000}")
    public void processQueue() {
        if (!enabled || submitterKey == null || submitterKey.isBlank()) {
            return;
        }
        var due = jdbc.queryForList("""
                SELECT id, state_code, function_name, args_json::text AS args, attempts
                  FROM chain.blockchain_transaction
                 WHERE (status = 'QUEUED' OR (status = 'FAILED' AND attempts < :max))
                   AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                 ORDER BY id LIMIT 20
                """, new MapSqlParameterSource("max", maxAttempts));
        for (Map<String, Object> row : due) {
            send(row);
        }
        var submitted = jdbc.queryForList("""
                SELECT id, tx_hash FROM chain.blockchain_transaction
                 WHERE status = 'SUBMITTED' ORDER BY id LIMIT 50
                """, Map.of());
        for (Map<String, Object> row : submitted) {
            confirm(((Number) row.get("id")).longValue(), (String) row.get("tx_hash"));
        }
    }

    private void send(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        int claimed = jdbc.update("""
                UPDATE chain.blockchain_transaction SET attempts = attempts + 1
                 WHERE id = :id AND attempts = :attempts AND status IN ('QUEUED', 'FAILED')
                """, new MapSqlParameterSource().addValue("id", id).addValue("attempts", attempts));
        if (claimed == 0) {
            return;
        }
        String stateCode = ((String) row.get("state_code")).trim();
        String address = contractAddress(stateCode);
        if (address == null) {
            markSkipped(id, "No deployed address configured for contract " + CONTRACT);
            return;
        }
        try {
            Map<String, Object> args = mapper.readValue((String) row.get("args"), new TypeReference<>() { });
            Function function = switch ((String) row.get("function_name")) {
                case "recordTokenState" -> new Function("recordTokenState", List.of(new Uint256(BigInteger.ZERO),
                        new Bytes32(Numeric.hexStringToByteArray((String) args.get("stateHash"))),
                        new Utf8String((String) args.get("tokenRef"))), List.of());
                case "recordDeed" -> new Function("recordDeed", List.of(new Utf8String((String) args.get("documentNo")),
                        new Bytes32(Numeric.hexStringToByteArray((String) args.get("deedHash")))), List.of());
                default -> throw new IllegalStateException("Unknown chain function " + row.get("function_name"));
            };
            var manager = new RawTransactionManager(web3j(), Credentials.create(submitterKey), chainId);
            var response = manager.sendTransaction(BigInteger.ZERO, BigInteger.valueOf(500_000L), address,
                    FunctionEncoder.encode(function), BigInteger.ZERO);
            if (response.hasError()) {
                markFailed(id, attempts + 1, response.getError().getMessage());
            } else {
                markSubmitted(id, response.getTransactionHash());
            }
        } catch (Exception e) {
            log.warn("Chain anchor {} failed: {}", id, e.getMessage());
            markFailed(id, attempts + 1, e.getMessage());
        }
    }

    private void confirm(long id, String txHash) {
        try {
            var receipt = web3j().ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isEmpty()) {
                return;
            }
            var r = receipt.get();
            if (r.isStatusOK()) {
                jdbc.update("""
                        UPDATE chain.blockchain_transaction SET status = 'MINED', block_number = :block,
                               gas_used = :gas, mined_at = now(), error_message = NULL WHERE id = :id
                        """, new MapSqlParameterSource().addValue("id", id)
                        .addValue("block", r.getBlockNumber().longValue()).addValue("gas", r.getGasUsed().longValue()));
            } else {
                jdbc.update("""
                        UPDATE chain.blockchain_transaction SET status = 'FAILED', attempts = :max,
                               block_number = :block, error_message = 'Reverted on chain' WHERE id = :id
                        """, new MapSqlParameterSource().addValue("id", id).addValue("max", maxAttempts)
                        .addValue("block", r.getBlockNumber().longValue()));
            }
        } catch (Exception e) {
            log.warn("Receipt check for anchor {} failed: {}", id, e.getMessage());
        }
    }

    /** True/false from the contract, or null when the chain is not connected or not reachable. */
    public Boolean hasState(String stateCode, String tokenRef, String stateHashHex) {
        List<Type> out = call(stateCode, new Function("hasState", List.of(new Utf8String(tokenRef),
                new Bytes32(Numeric.hexStringToByteArray(stateHashHex))), List.of(new org.web3j.abi.TypeReference<Bool>() { })));
        return out == null || out.isEmpty() ? null : (Boolean) out.get(0).getValue();
    }

    /** The deed hash recorded on chain (0x-hex), "" when none is recorded, or null when not connected. */
    public String deedOf(String stateCode, String documentNo) {
        List<Type> out = call(stateCode, new Function("deedOf", List.of(new Utf8String(documentNo)),
                List.of(new org.web3j.abi.TypeReference<Bytes32>() { })));
        if (out == null || out.isEmpty()) {
            return null;
        }
        byte[] value = (byte[]) out.get(0).getValue();
        return new BigInteger(1, value).signum() == 0 ? "" : Numeric.toHexString(value);
    }

    @SuppressWarnings("rawtypes")
    private List<Type> call(String stateCode, Function function) {
        if (!connected(stateCode)) {
            return null;
        }
        try {
            String from = Credentials.create(submitterKey).getAddress();
            var result = web3j().ethCall(Transaction.createEthCallTransaction(from, contractAddress(stateCode),
                    FunctionEncoder.encode(function)), DefaultBlockParameterName.LATEST).send();
            if (result.hasError() || result.getValue() == null) {
                return null;
            }
            return FunctionReturnDecoder.decode(result.getValue(), function.getOutputParameters());
        } catch (Exception e) {
            log.warn("Chain read {} failed: {}", function.getName(), e.getMessage());
            return null;
        }
    }

    synchronized Web3j web3j() {
        if (web3j == null) {
            web3j = Web3j.build(new HttpService(rpcUrl));
        }
        return web3j;
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
                .addValue("network", networkCode(request.stateCode()))
                .addValue("contract", request.contractCode())
                .addValue("function", request.functionName())
                .addValue("args", json(request.args()))
                .addValue("key", UUID.randomUUID())
                .addValue("txnId", request.transactionId())
                .addValue("tokenId", request.tokenId()), keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    String networkCode(String stateCode) {
        String code = jdbc.query("""
                SELECT network_code FROM cfg.chain_network_config
                 WHERE state_code = :stateCode ORDER BY id LIMIT 1
                """, new MapSqlParameterSource("stateCode", stateCode), rs -> rs.next() ? rs.getString(1) : null);
        return code == null ? "SLATE_LOCAL" : code;
    }

    String contractAddress(String stateCode) {
        return jdbc.query("""
                SELECT c.address FROM cfg.chain_contract c
                 WHERE c.network_code = :networkCode AND c.contract_code = :contractCode AND c.active
                 ORDER BY c.id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("networkCode", networkCode(stateCode))
                .addValue("contractCode", CONTRACT), rs -> rs.next() ? rs.getString(1) : null);
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

    private void markSubmitted(long id, String txHash) {
        jdbc.update("""
                UPDATE chain.blockchain_transaction SET status = 'SUBMITTED', tx_hash = :txHash, error_message = NULL
                 WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("txHash", txHash));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise chain args", e);
        }
    }
}
