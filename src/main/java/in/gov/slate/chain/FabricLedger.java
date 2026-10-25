package in.gov.slate.chain;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.Hash;
import org.hyperledger.fabric.client.Network;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import jakarta.annotation.PreDestroy;

/**
 * Fabric Gateway connections for the three SLATE organisations. Each officer role signs as its own
 * organisation (Registration, Revenue or Survey); the gateway peer collects the 2-of-3 endorsements
 * the channel requires and sends the transaction to the orderer.
 */
@Component
public class FabricLedger {

    private static final Logger log = LoggerFactory.getLogger(FabricLedger.class);

    public enum Org {
        REGISTRATION("RegistrationMSP", "registration"),
        REVENUE("RevenueMSP", "revenue"),
        SURVEY("SurveyMSP", "survey");

        final String mspId;
        final String domain;

        Org(String mspId, String name) {
            this.mspId = mspId;
            this.domain = name + ".slate.local";
        }

        public String mspId() {
            return mspId;
        }

        public static Org forMsp(String msp) {
            for (Org o : values()) {
                if (o.mspId.equalsIgnoreCase(msp)) {
                    return o;
                }
            }
            return REGISTRATION;
        }

        /** The organisation that signs for a SLATE officer role. */
        public static Org forRole(String role) {
            if (role == null) {
                return REGISTRATION;
            }
            return switch (role.toUpperCase()) {
                case "VAO", "TAHSILDAR", "REVENUE_INSPECTOR", "RI" -> REVENUE;
                case "SURVEYOR", "SURVEY_OFFICER" -> SURVEY;
                default -> REGISTRATION;
            };
        }
    }

    /** A chaincode rejection (business rule) as opposed to a network failure. */
    public static class ChaincodeRejected extends RuntimeException {
        public ChaincodeRejected(String message) {
            super(message);
        }
    }

    public record Submitted(String txId, long blockNumber, String validationCode) {
    }

    private final Path cryptoPath;
    private final String channel;
    private final String chaincode;
    private final Map<Org, String> endpoints;
    private final Map<Org, Gateway> gateways = new ConcurrentHashMap<>();
    private final Map<Org, ManagedChannel> channels = new ConcurrentHashMap<>();

    public FabricLedger(@Value("${slate.chain.fabric.crypto-path:./fabric/organizations}") String cryptoPath,
                        @Value("${slate.chain.fabric.channel:slate}") String channel,
                        @Value("${slate.chain.fabric.chaincode:slateland}") String chaincode,
                        @Value("${slate.chain.fabric.registration-peer:localhost:7051}") String registrationPeer,
                        @Value("${slate.chain.fabric.revenue-peer:localhost:8051}") String revenuePeer,
                        @Value("${slate.chain.fabric.survey-peer:localhost:9051}") String surveyPeer) {
        this.cryptoPath = Path.of(cryptoPath).toAbsolutePath().normalize();
        this.channel = channel;
        this.chaincode = chaincode;
        this.endpoints = Map.of(Org.REGISTRATION, registrationPeer, Org.REVENUE, revenuePeer, Org.SURVEY, surveyPeer);
    }

    public String channel() {
        return channel;
    }

    public String chaincode() {
        return chaincode;
    }

    /** Null when every organisation's local identity is present, otherwise why the ledger can't be used. */
    public String missingCrypto() {
        for (Org org : Org.values()) {
            if (!Files.isDirectory(userMsp(org))) {
                return "Fabric identity for " + org.mspId + " not found under " + cryptoPath
                        + " (start fabric/docker-compose.yml first)";
            }
        }
        return null;
    }

    public Submitted submit(Org org, String function, String... args) {
        Contract contract = contract(org);
        try {
            var tx = contract.newProposal(function).addArguments(args).build().endorse().submitAsync();
            var status = tx.getStatus();
            if (!status.isSuccessful()) {
                throw new ChaincodeRejected("Transaction " + tx.getTransactionId() + " invalid: " + status.getCode());
            }
            return new Submitted(tx.getTransactionId(), status.getBlockNumber(), status.getCode().name());
        } catch (EndorseException e) {
            throw classify(e);
        } catch (GatewayException e) {
            throw new IllegalStateException(describe(e), e);
        }
    }

    public String evaluate(Org org, String function, String... args) {
        try {
            return new String(contract(org).evaluateTransaction(function, args), StandardCharsets.UTF_8);
        } catch (GatewayException e) {
            throw classify(e);
        }
    }

    /** Block number of a committed transaction, or -1 when the ledger doesn't know it (yet). */
    public long blockOf(String txId) {
        try {
            byte[] raw = network(Org.REGISTRATION).getContract("qscc")
                    .evaluateTransaction("GetBlockByTxID", channel, txId);
            return org.hyperledger.fabric.protos.common.Block.parseFrom(raw).getHeader().getNumber();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Current block height of the channel, or -1 when unreachable. */
    public long height() {
        return height(Org.REGISTRATION);
    }

    /** Block height as seen by one organisation's peer, or -1 when that peer is unreachable. */
    public long height(Org peerOrg) {
        try {
            byte[] raw = network(peerOrg).getContract("qscc").evaluateTransaction("GetChainInfo", channel);
            return org.hyperledger.fabric.protos.common.BlockchainInfo.parseFrom(raw).getHeight();
        } catch (Exception e) {
            return -1;
        }
    }

    private RuntimeException classify(GatewayException e) {
        String detail = describe(e);
        for (String code : List.of("DOUBLE_SPEND", "TRANSFER_BLOCKED", "ACCESS_DENIED", "DUPLICATE_", "INVALID_",
                "NOT_FOUND", "DEED_EXISTS", "ALREADY_RELEASED", "expected ")) {
            if (detail.contains(code)) {
                return new ChaincodeRejected(detail);
            }
        }
        return new IllegalStateException(detail, e);
    }

    private static String describe(GatewayException e) {
        var details = e.getDetails();
        if (details == null || details.isEmpty()) {
            return e.getMessage();
        }
        String msg = details.get(0).getMessage();
        int i = msg.indexOf("message: ");
        msg = i >= 0 ? msg.substring(i + 9) : msg;
        return msg.replaceFirst("^chaincode response \\d+, ", "");
    }

    private Contract contract(Org org) {
        return network(org).getContract(chaincode);
    }

    private Network network(Org org) {
        return gateway(org).getNetwork(channel);
    }

    private Gateway gateway(Org org) {
        return gateways.computeIfAbsent(org, this::connect);
    }

    private Gateway connect(Org org) {
        try {
            Path peerTls = cryptoPath.resolve("peerOrganizations/" + org.domain + "/peers/peer0." + org.domain
                    + "/tls/ca.crt");
            ManagedChannel grpc = Grpc.newChannelBuilder(endpoints.get(org),
                            TlsChannelCredentials.newBuilder().trustManager(peerTls.toFile()).build())
                    .overrideAuthority("peer0." + org.domain).build();
            channels.put(org, grpc);
            Path msp = userMsp(org);
            X509Identity identity;
            try (Reader r = Files.newBufferedReader(firstFile(msp.resolve("signcerts")))) {
                identity = new X509Identity(org.mspId, Identities.readX509Certificate(r));
            }
            java.security.PrivateKey key;
            try (Reader r = Files.newBufferedReader(firstFile(msp.resolve("keystore")))) {
                key = Identities.readPrivateKey(r);
            }
            log.info("Connecting Fabric gateway as {} to {}", org.mspId, endpoints.get(org));
            return Gateway.newInstance().identity(identity).signer(Signers.newPrivateKeySigner(key))
                    .hash(Hash.SHA256).connection(grpc)
                    .evaluateOptions(o -> o.withDeadlineAfter(10, TimeUnit.SECONDS))
                    .endorseOptions(o -> o.withDeadlineAfter(20, TimeUnit.SECONDS))
                    .submitOptions(o -> o.withDeadlineAfter(10, TimeUnit.SECONDS))
                    .commitStatusOptions(o -> o.withDeadlineAfter(60, TimeUnit.SECONDS))
                    .connect();
        } catch (IOException | java.security.cert.CertificateException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("Cannot load Fabric identity for " + org.mspId + ": " + e.getMessage(), e);
        }
    }

    private Path userMsp(Org org) {
        return cryptoPath.resolve("peerOrganizations/" + org.domain + "/users/User1@" + org.domain + "/msp");
    }

    private static Path firstFile(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).findFirst()
                    .orElseThrow(() -> new IOException("No file in " + dir));
        }
    }

    @PreDestroy
    public void close() {
        gateways.values().forEach(Gateway::close);
        channels.values().forEach(c -> {
            try {
                c.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
