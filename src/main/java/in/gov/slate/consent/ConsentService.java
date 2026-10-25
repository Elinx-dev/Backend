package in.gov.slate.consent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.config.ConfigService;
import in.gov.slate.connectors.AadhaarConnector;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.TransactionService;
import in.gov.slate.transaction.WorkflowEngine;

/**
 * Aadhaar OTP consent, captured per party. Only the salted hash, the last four
 * digits and the OTP request reference are persisted.
 */
@Service
public class ConsentService {

    /** State admin feature flag: on = real Aadhaar OTP API, off = mock OTP. */
    public static final String AADHAAR_OTP_LIVE = "AADHAAR_OTP_LIVE";
    private static final java.util.regex.Pattern AADHAAR = java.util.regex.Pattern.compile("\\d{12}");

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, AadhaarConnector> connectors = new java.util.HashMap<>();
    private final ConfigService config;
    private final TransactionRepository repository;
    private final WorkflowEngine workflow;
    private final AuditService audit;
    private final TransactionService transactions;

    public ConsentService(NamedParameterJdbcTemplate jdbc, List<AadhaarConnector> aadhaarConnectors,
                          ConfigService config, TransactionRepository repository, WorkflowEngine workflow,
                          AuditService audit, TransactionService transactions) {
        this.jdbc = jdbc;
        aadhaarConnectors.forEach(c -> connectors.put(c.mode(), c));
        this.config = config;
        this.repository = repository;
        this.workflow = workflow;
        this.audit = audit;
        this.transactions = transactions;
    }

    private AadhaarConnector connector(String mode) {
        AadhaarConnector connector = connectors.get(mode == null ? "MOCK" : mode);
        if (connector == null) {
            throw new IllegalStateException("No Aadhaar connector for mode " + mode);
        }
        return connector;
    }

    @Transactional
    public List<Map<String, Object>> requestOtp(String txnRef, List<Long> partyIds) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("CONSENT_CAPTURE");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        if (transactions.fillMissingOwnerAadhaar(ctx)) {
            ctx = repository.load(txnRef, user.stateCode());
        }
        if ("DRAFT".equals(ctx.status())) {
            workflow.apply(ctx, "REQUEST_CONSENT", null, user);
            ctx = repository.load(txnRef, user.stateCode());
        }
        workflow.requireStatus(ctx, "CONSENT_PENDING", "Consent OTP request", user);
        String consentTextVersion = consentTextVersion();
        String mode = config.flagEnabled(user.stateCode(), AADHAAR_OTP_LIVE) ? "LIVE" : "MOCK";
        AadhaarConnector aadhaar = connector(mode);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> party : ctx.parties()) {
            long partyId = ((Number) party.get("id")).longValue();
            if (partyIds != null && !partyIds.isEmpty() && !partyIds.contains(partyId)
                    || Boolean.TRUE.equals(party.get("deceased"))) {
                continue;
            }
            if (!Boolean.TRUE.equals(party.get("aadhaar_captured"))) {
                throw ApiException.badRequest("Party " + party.get("name") + " has no Aadhaar on record");
            }
            String number = (String) party.get("aadhaar_last4");
            if ("LIVE".equals(mode)) {
                number = transactions.partyAadhaarForOtp(partyId);
                if (number == null || !AADHAAR.matcher(number).matches()) {
                    throw ApiException.badRequest("Re-enter the 12-digit Aadhaar of " + party.get("name")
                            + ": the live Aadhaar OTP service needs the full number");
                }
            }
            var result = aadhaar.requestOtp(number, consentTextVersion);
            jdbc.update("""
                    INSERT INTO core.consent_record (transaction_id, party_id, aadhaar_hash, otp_request_reference,
                        status, requested_at, officer_user_id, consent_text_version, otp_mode)
                    SELECT :txnId, p.id, p.aadhaar_hash, :reference, 'PENDING', now(), :officer, :consentVersion,
                           :mode
                      FROM core.transaction_party p
                     WHERE p.id = :partyId AND p.transaction_id = :txnId
                    ON CONFLICT (transaction_id, party_id) DO UPDATE
                        SET otp_request_reference = EXCLUDED.otp_request_reference,
                            status = 'PENDING',
                            requested_at = now(),
                            attempt_count = 0,
                            verified_at = NULL,
                            otp_mode = EXCLUDED.otp_mode
                    """, new MapSqlParameterSource()
                    .addValue("txnId", ctx.id())
                    .addValue("partyId", partyId)
                    .addValue("reference", result.requestReference())
                    .addValue("officer", user.id())
                    .addValue("consentVersion", consentTextVersion)
                    .addValue("mode", mode));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("partyId", partyId);
            row.put("partyName", party.get("name"));
            row.put("otpRequestReference", result.requestReference());
            row.put("maskedTarget", result.maskedTarget());
            row.put("validitySeconds", result.validitySeconds());
            row.put("otpMode", mode);
            if (result.demoOtp() != null) {
                row.put("demoOtp", result.demoOtp());
            }
            out.add(row);

            audit.record("CONSENT_OTP_REQUESTED", "CONSENT", String.valueOf(partyId), txnRef, ctx.propertyRef(),
                    Map.of("otpRequestReference", result.requestReference()), null);
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("No matching party on transaction " + txnRef);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> verify(String txnRef, long partyId, String otp) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("CONSENT_CAPTURE");
        var ctx = repository.load(txnRef, user.stateCode());
        workflow.requireStatus(ctx, "CONSENT_PENDING", "Consent verification", user);

        var rows = jdbc.queryForList("""
                SELECT id, otp_request_reference, status, attempt_count, otp_mode
                  FROM core.consent_record WHERE transaction_id = :txnId AND party_id = :partyId
                """, new MapSqlParameterSource().addValue("txnId", ctx.id()).addValue("partyId", partyId));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Consent request for party " + partyId);
        }
        Map<String, Object> consent = rows.get(0);
        if ("VERIFIED".equals(consent.get("status"))) {
            return verificationResult(partyId, ctx, user);
        }
        long consentId = ((Number) consent.get("id")).longValue();
        boolean ok = connector((String) consent.get("otp_mode"))
                .verifyOtp((String) consent.get("otp_request_reference"), otp);

        jdbc.update("""
                UPDATE core.consent_record
                   SET status = :status,
                       verified_at = CASE WHEN :status = 'VERIFIED' THEN now() ELSE verified_at END,
                       attempt_count = attempt_count + 1
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", consentId)
                .addValue("status", ok ? "VERIFIED" : "FAILED"));

        audit.record("CONSENT_OTP_VERIFIED", "CONSENT", String.valueOf(partyId), txnRef, ctx.propertyRef(),
                null, Map.of("verified", ok), ok ? "SUCCESS" : "FAILURE", null);

        if (!ok) {
            throw ApiException.badRequest("Incorrect OTP for this party");
        }
        transactions.forgetPartyAadhaar(partyId);
        return verificationResult(partyId, repository.load(txnRef, user.stateCode()), user);
    }

    public List<Map<String, Object>> status(String txnRef) {
        CurrentUser user = CurrentUser.require();
        var ctx = repository.load(txnRef, user.stateCode());
        return jdbc.queryForList("""
                SELECT p.id AS party_id, p.name, p.side, p.aadhaar_last4,
                       coalesce(c.status, 'NOT_REQUESTED') AS status,
                       c.otp_request_reference, c.requested_at, c.verified_at, c.attempt_count
                  FROM core.transaction_party p
                  LEFT JOIN core.consent_record c ON c.party_id = p.id
                 WHERE p.transaction_id = :txnId
                 ORDER BY p.side, p.seq
                """, new MapSqlParameterSource("txnId", ctx.id()));
    }

    private String consentTextVersion() {
        String value = jdbc.query("SELECT value_json #>> '{}' FROM master.system_config WHERE key = 'consent.text.version'",
                new MapSqlParameterSource(), rs -> rs.next() ? rs.getString(1) : null);
        return value == null ? "CONSENT-V1" : value;
    }

    private Map<String, Object> verificationResult(long partyId, TransactionContext ctx, CurrentUser user) {
        boolean allPartiesVerified = allPartiesVerified(ctx);
        String transactionStatus = ctx.status();
        if (allPartiesVerified && "CONSENT_PENDING".equals(transactionStatus)) {
            transactionStatus = workflow.apply(ctx, "CONSENT_COMPLETE", null, user);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("partyId", partyId);
        result.put("status", "VERIFIED");
        result.put("allPartiesVerified", allPartiesVerified);
        result.put("transactionStatus", transactionStatus);
        return result;
    }

    private boolean allPartiesVerified(TransactionContext ctx) {
        if (ctx.parties().isEmpty()) {
            return false;
        }
        var verifiedPartyIds = ctx.consents().stream()
                .filter(consent -> "VERIFIED".equals(consent.get("status")))
                .map(consent -> ((Number) consent.get("party_id")).longValue())
                .toList();
        return ctx.parties().stream()
                .filter(party -> !Boolean.TRUE.equals(party.get("deceased")))
                .allMatch(party -> verifiedPartyIds.contains(((Number) party.get("id")).longValue()));
    }
}
