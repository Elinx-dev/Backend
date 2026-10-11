package in.gov.slate.chain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.tx.RawTransactionManager;

import in.gov.slate.common.Hashes;

/**
 * Local development only ({@code slate.chain.dev-deploy=true}): deploys the bundled
 * SlatePropertyToken contract to the local Besu dev node and registers its address in
 * cfg.chain_contract. Re-deploys when the dev chain was reset and the old address has no code.
 */
@Component
public class ChainDevDeployer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChainDevDeployer.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ChainService chain;
    private final boolean devDeploy;
    private final long chainId;
    private final String submitterKey;

    public ChainDevDeployer(NamedParameterJdbcTemplate jdbc, ChainService chain,
                            @Value("${slate.chain.dev-deploy:false}") boolean devDeploy,
                            @Value("${slate.chain.chain-id}") long chainId,
                            @Value("${slate.chain.submitter-private-key:}") String submitterKey) {
        this.jdbc = jdbc;
        this.chain = chain;
        this.devDeploy = devDeploy;
        this.chainId = chainId;
        this.submitterKey = submitterKey;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!devDeploy || submitterKey == null || submitterKey.isBlank()) {
            return;
        }
        var web3j = chain.web3j();
        for (String network : jdbc.queryForList(
                "SELECT DISTINCT network_code FROM cfg.chain_network_config WHERE active", Map.of(), String.class)) {
            String existing = jdbc.query("""
                    SELECT address FROM cfg.chain_contract
                     WHERE network_code = :n AND contract_code = :c AND active ORDER BY id DESC LIMIT 1
                    """, new MapSqlParameterSource("n", network).addValue("c", ChainService.CONTRACT),
                    rs -> rs.next() ? rs.getString(1) : null);
            if (existing != null) {
                String code = web3j.ethGetCode(existing, DefaultBlockParameterName.LATEST).send().getCode();
                if (code != null && code.length() > 2) {
                    continue;
                }
                jdbc.update("UPDATE cfg.chain_contract SET active = false WHERE network_code = :n AND contract_code = :c",
                        new MapSqlParameterSource("n", network).addValue("c", ChainService.CONTRACT));
            }
            String bin = new String(new ClassPathResource("chain/SlatePropertyToken.bin").getInputStream()
                    .readAllBytes(), StandardCharsets.UTF_8).trim();
            String abi = new String(new ClassPathResource("chain/SlatePropertyToken.abi").getInputStream()
                    .readAllBytes(), StandardCharsets.UTF_8);
            var credentials = Credentials.create(submitterKey);
            BigInteger nonce = web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
            var tx = RawTransaction.createContractTransaction(nonce, BigInteger.ZERO, BigInteger.valueOf(3_000_000L),
                    BigInteger.ZERO, "0x" + bin);
            var sent = new RawTransactionManager(web3j, credentials, chainId).signAndSend(tx);
            if (sent.hasError()) {
                log.warn("Dev contract deploy failed: {}", sent.getError().getMessage());
                return;
            }
            for (int i = 0; i < 30; i++) {
                var receipt = web3j.ethGetTransactionReceipt(sent.getTransactionHash()).send().getTransactionReceipt();
                if (receipt.isPresent()) {
                    jdbc.update("""
                            INSERT INTO cfg.chain_contract (network_code, contract_code, address, abi_sha256,
                                deployed_block, deployed_at, deployer_address, source_commit, active)
                            VALUES (:n, :c, :address, :abi, :block, now(), :deployer, 'dev-deploy', true)
                            """, new MapSqlParameterSource("n", network).addValue("c", ChainService.CONTRACT)
                            .addValue("address", receipt.get().getContractAddress())
                            .addValue("abi", Hashes.sha256(abi))
                            .addValue("block", receipt.get().getBlockNumber().longValue())
                            .addValue("deployer", credentials.getAddress()));
                    log.info("Deployed {} on {} at {}", ChainService.CONTRACT, network, receipt.get().getContractAddress());
                    break;
                }
                Thread.sleep(1000);
            }
        }
    }
}
