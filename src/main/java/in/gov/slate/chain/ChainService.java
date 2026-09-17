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
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;

/**
 * Anchors token state on the local Besu network. When the chain is switched off
 * or not reachable the anchor is recorded as SKIPPED: the registry keeps working
 * and the off-chain state hash stays verifiable.
 */
@Service
public class ChainService {

    private static final Logger log = LoggerFactory.getLogger(ChainService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String rpcUrl;
    private final long chainId;
    private final String submitterKey;

    public ChainService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
                        @Value("${slate.chain.enabled}") boolean enabled,
                        @Value("${slate.chain.rpc-primary}") String rpcUrl,
                        @Value("${slate.chain.chain-id}") long chainId,
                        @Value("${slate.chain.submitter-private-key:}") String submitterKey) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.enabled = enabled;
        this.rpcUrl = rpcUrl;
        this.chainId = chainId;
        this.submitterKey = submitterKey;
    }

    public record AnchorRequest(String stateCode, String contractCode, String functionName,
                                Map<String, Object> args, Long transactionId, Long tokenId,
                                BigInteger onchainTokenId, byte[] stateHash, String tokenRef) {
    }

    /** Returns the transaction hash when the anchor was mined, otherwise null. */
    public String anchor(AnchorRequest request) {
        long rowId = queue(request);
        if (!enabled) {
            markSkipped(rowId, "Chain anchoring is disabled (slate.chain.enabled=false)");
            return null;
        }
        String contractAddress = contractAddress(request.stateCode(), request.contractCode());
        if (contractAddress == null) {
            markSkipped(rowId, "No deployed address configured for contract " + request.contractCode());
            return null;
        }
        if (submitterKey == null || submitterKey.isBlank()) {
            markSkipped(rowId, "No submitter key is configured; anchoring requires Vault or a local demo key");
            return null;
        }
        try {
            Web3j web3j = Web3j.build(new HttpService(rpcUrl));
            var credentials = Credentials.create(submitterKey);
            var manager = new RawTransactionManager(web3j, credentials, chainId);
            Function function = new Function(request.functionName(),
                    List.of(new Uint256(request.onchainTokenId() == null ? BigInteger.ZERO : request.onchainTokenId()),
                            new Bytes32(request.stateHash()),
                            new Utf8String(request.tokenRef())),
                    List.of());
            var response = manager.sendTransaction(BigInteger.valueOf(1_000_000_000L),
                    BigInteger.valueOf(4_000_000L), contractAddress, FunctionEncoder.encode(function),
                    BigInteger.ZERO);
            if (response.hasError()) {
                markFailed(rowId, response.getError().getMessage());
                return null;
            }
            markSubmitted(rowId, response.getTransactionHash());
            return response.getTransactionHash();
        } catch (Exception e) {
            log.warn("Chain anchor failed for {}: {}", request.tokenRef(), e.getMessage());
            markFailed(rowId, e.getMessage());
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
                .addValue("network", networkCode(request.stateCode()))
                .addValue("contract", request.contractCode())
                .addValue("function", request.functionName())
                .addValue("args", json(request.args()))
                .addValue("key", UUID.randomUUID())
                .addValue("txnId", request.transactionId())
                .addValue("tokenId", request.tokenId()), keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    private String networkCode(String stateCode) {
        String code = jdbc.query("""
                SELECT network_code FROM cfg.chain_network_config
                 WHERE state_code = :stateCode ORDER BY id LIMIT 1
                """, new MapSqlParameterSource("stateCode", stateCode), rs -> rs.next() ? rs.getString(1) : null);
        return code == null ? "SLATE_LOCAL" : code;
    }

    private String contractAddress(String stateCode, String contractCode) {
        return jdbc.query("""
                SELECT c.address FROM cfg.chain_contract c
                 WHERE c.network_code = :networkCode AND c.contract_code = :contractCode AND c.active
                 ORDER BY c.id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("networkCode", networkCode(stateCode))
                .addValue("contractCode", contractCode), rs -> rs.next() ? rs.getString(1) : null);
    }

    private void markSkipped(long id, String reason) {
        jdbc.update("UPDATE chain.blockchain_transaction SET status = 'SKIPPED', error_message = :reason WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("reason", reason));
    }

    private void markFailed(long id, String reason) {
        jdbc.update("UPDATE chain.blockchain_transaction SET status = 'FAILED', error_message = :reason WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("reason", reason));
    }

    private void markSubmitted(long id, String txHash) {
        jdbc.update("""
                UPDATE chain.blockchain_transaction SET status = 'SUBMITTED', tx_hash = :txHash WHERE id = :id
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
