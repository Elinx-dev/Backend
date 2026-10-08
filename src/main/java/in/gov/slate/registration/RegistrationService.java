package in.gov.slate.registration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.chain.TokenService;
import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.Hashes;
import in.gov.slate.common.NumberingService;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.ValidationEngine;
import in.gov.slate.transaction.WorkflowEngine;

/**
 * Registration is the final registration-side state. It writes the registered
 * document, recomputes registered ownership, mints or updates the property token
 * and hands the transaction to the Revenue side, which then proceeds independently.
 */
@Service
public class RegistrationService {


    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final ValidationEngine validation;
    private final WorkflowEngine workflow;
    private final TokenService tokens;
    private final NumberingService numbering;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public RegistrationService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository,
                               ValidationEngine validation, WorkflowEngine workflow, TokenService tokens,
                               NumberingService numbering, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.validation = validation;
        this.workflow = workflow;
        this.tokens = tokens;
        this.numbering = numbering;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> register(String txnRef) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("REGISTER");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        if ("REGISTERED".equals(ctx.status())) {
            throw ApiException.conflict("Transaction " + txnRef + " is already registered");
        }
        workflow.requireAvailable(ctx, "REGISTER", user);
        validation.evaluate(ctx, "REGISTRATION", true);

        List<TokenService.Owner> resultingOwners = resultingOwners(ctx);
        byte[] deedHash = deedHash(ctx, resultingOwners);
        String documentNo = numbering.next((String) ctx.transaction().get("state_code"), "REG_DOC_NO", null);
        LocalDate today = LocalDate.now();

        var keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO core.registration_result (transaction_id, registered_document_no, registration_year,
                    registration_date, registering_sro, deed_sha256, registration_reference)
                VALUES (:txnId, :documentNo, :year, :date, :sro, :deedHash, :reference)
                """, new MapSqlParameterSource()
                .addValue("txnId", ctx.id())
                .addValue("documentNo", documentNo)
                .addValue("year", today.getYear())
                .addValue("date", today)
                .addValue("sro", ctx.transaction().get("sro_code"))
                .addValue("deedHash", deedHash)
                .addValue("reference", txnRef), keyHolder, new String[]{"id"});
        long registrationId = keyHolder.getKey().longValue();

        // registered_at is frozen by core.guard_registered_transaction once the row
        // reaches REGISTERED, so it is stamped before the transition is applied.
        jdbc.update("UPDATE core.transaction SET registered_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", ctx.id()));
        String newStatus = workflow.apply(ctx, "REGISTER", null, user);

        long propertyId = ((Number) ctx.property().get("id")).longValue();
        applyRegisteredOwnership(ctx, propertyId, resultingOwners, today);

        byte[] evidenceRoot = Hashes.merkleRoot(List.of(deedHash, TokenService.ownerSetHash(resultingOwners),
                Hashes.sha256(txnRef)));
        Map<String, Object> token = tokens.mintOrUpdate((String) ctx.transaction().get("state_code"), propertyId,
                ctx.propertyRef(), ctx.id(), resultingOwners, evidenceRoot);

        audit.record("REGISTERED", "TRANSACTION", String.valueOf(ctx.id()), txnRef, ctx.propertyRef(),
                Map.of("documentNo", documentNo, "token", String.valueOf(token.get("token_ref"))), null);

        TransactionContext registered = repository.load(txnRef, user.stateCode());
        String handoff = handOff(registered, registrationId, resultingOwners, user);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("txnRef", txnRef);
        out.put("status", handoff == null ? newStatus : handoff);
        out.put("registeredDocumentNo", documentNo);
        out.put("registrationDate", today);
        out.put("registeringSro", ctx.transaction().get("sro_code"));
        out.put("deedSha256", Hashes.hex(deedHash));
        out.put("token", token);
        out.put("registeredOwners", resultingOwners);
        return out;
    }

    /**
     * After registration the transaction either goes to survey (physical partial
     * extent or partition) or straight to the Revenue queue.
     */
    private String handOff(TransactionContext ctx, long registrationId, List<TokenService.Owner> owners,
                           CurrentUser user) {
        if (ctx.surveyRequired()) {
            return workflow.apply(ctx, "START_SURVEY", null, user);
        }
        createProposedMutation(ctx, registrationId, owners, null);
        return workflow.apply(ctx, "PROPOSE_MUTATION", null, user);
    }

    public void createProposedMutation(TransactionContext ctx, long registrationId, List<TokenService.Owner> owners,
                                       Long surveySubmissionId) {
        Long currentStateId = jdbc.query("""
                SELECT id FROM revenue.current_state WHERE property_id = :propertyId
                 ORDER BY fetched_at DESC LIMIT 1
                """, new MapSqlParameterSource("propertyId", ctx.property().get("id")),
                rs -> rs.next() ? rs.getLong(1) : null);

        jdbc.update("""
                INSERT INTO revenue.proposed_mutation (state_code, transaction_id, property_id,
                    registration_result_id, current_state_id, proposed_owner_set, resulting_ownership,
                    mutation_type, survey_submission_id, status)
                VALUES (:stateCode, :txnId, :propertyId, :registrationId, :currentStateId,
                    cast(:owners AS jsonb), cast(:owners AS jsonb), :mutationType, :submissionId, 'VAO_PENDING')
                ON CONFLICT (transaction_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.transaction().get("state_code"))
                .addValue("txnId", ctx.id())
                .addValue("propertyId", ctx.property().get("id"))
                .addValue("registrationId", registrationId)
                .addValue("currentStateId", currentStateId)
                .addValue("owners", json(owners))
                .addValue("mutationType", mutationType(ctx))
                .addValue("submissionId", surveySubmissionId));
    }

    private String mutationType(TransactionContext ctx) {
        if (Boolean.TRUE.equals(ctx.transaction().get("subdivision_required"))) {
            return "SUBDIVISION";
        }
        return switch (ctx.deedTypeCode()) {
            case "SALE" -> "UNDIVIDED_SHARE".equals(ctx.transferScope()) ? "UNDIVIDED_SHARE" : "FULL_PROPERTY_TRANSFER";
            case "RELEASE" -> "RELEASE_RELINQUISHMENT";
            case "PARTITION" -> "PHYSICAL_PARTIAL_EXTENT_SUBDIVISION".equals(ctx.transferScope())
                    ? "SUBDIVISION" : "OTHER";
            case "SALE_FULL" -> "FULL_PROPERTY_TRANSFER";
            case "SALE_UNDIVIDED_SHARE" -> "UNDIVIDED_SHARE";
            case "SALE_PARTIAL_SUBDIVISION" -> "SUBDIVISION";
            case "GIFT", "SETTLEMENT" -> "GIFT_SETTLEMENT_TRANSFER";
            case "RELEASE_RELINQUISHMENT" -> "RELEASE_RELINQUISHMENT";
            default -> "OTHER";
        };
    }

    /**
     * Registered ownership after the transfer: the transferring owners (first party) leave the record and the
     * incoming parties (second party) are added. Shares are not recorded on transactions, so new owners carry
     * none; owners who are not party to the transfer keep what they had.
     */
    public List<TokenService.Owner> resultingOwners(TransactionContext ctx) {
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        for (Map<String, Object> owner : ctx.propertyOwners()) {
            shares.putIfAbsent((String) owner.get("owner_name"), decimal(owner.get("share_pct")));
        }
        // A Partition keeps living co-owners; only deceased owners leave, replaced by their heirs.
        boolean partition = "PARTITION".equals(ctx.deedTypeCode());
        ctx.side("SIDE_1").stream()
                .filter(party -> !partition || Boolean.TRUE.equals(party.get("deceased")))
                .forEach(party -> shares.remove((String) party.get("name")));
        ctx.side("SIDE_2").forEach(party -> shares.put((String) party.get("name"), null));

        List<TokenService.Owner> out = new ArrayList<>();
        shares.forEach((name, share) -> out.add(new TokenService.Owner(name, share)));
        if (out.isEmpty()) {
            throw ApiException.conflict("Registration would leave the property without any owner");
        }
        return out;
    }

    private void applyRegisteredOwnership(TransactionContext ctx, long propertyId, List<TokenService.Owner> owners,
                                          LocalDate effectiveFrom) {
        jdbc.update("""
                UPDATE core.property_owner SET effective_to = :today
                 WHERE property_id = :propertyId AND effective_to IS NULL
                """, new MapSqlParameterSource().addValue("propertyId", propertyId).addValue("today", effectiveFrom));
        for (TokenService.Owner owner : owners) {
            Map<String, Object> d = ownerDetails(ctx, owner.name());
            jdbc.update("""
                    INSERT INTO core.property_owner (property_id, owner_name, share_pct, source, transaction_id,
                        effective_from, owner_type_code, aadhaar_number, pan, mobile, address, registration_no,
                        representative_role, representative_name, representative_designation,
                        representative_aadhaar, representative_pan, representative_mobile)
                    VALUES (:propertyId, :name, :share, 'REGISTRATION', :txnId,
                        :today, :ownerType, :aadhaar, :pan, :mobile, :address, :registrationNo,
                        :repRole, :repName, :repDesignation,
                        :repAadhaar, :repPan, :repMobile)
                    """, new MapSqlParameterSource()
                    .addValue("propertyId", propertyId)
                    .addValue("name", owner.name())
                    .addValue("share", owner.sharePct())
                    .addValue("txnId", ctx.id())
                    .addValue("today", effectiveFrom)
                    .addValue("ownerType", d.get("owner_type_code"))
                    .addValue("aadhaar", d.get("aadhaar_number"))
                    .addValue("pan", d.get("pan"))
                    .addValue("mobile", d.get("mobile"))
                    .addValue("address", d.get("address"))
                    .addValue("registrationNo", d.get("registration_no"))
                    .addValue("repRole", d.get("representative_role"))
                    .addValue("repName", d.get("representative_name"))
                    .addValue("repDesignation", d.get("representative_designation"))
                    .addValue("repAadhaar", d.get("representative_aadhaar"))
                    .addValue("repPan", d.get("representative_pan"))
                    .addValue("repMobile", d.get("representative_mobile")));
        }
        ctx.side("SIDE_2").stream()
                .map(party -> (String) party.get("owner_type_code"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .ifPresent(type -> jdbc.update("UPDATE core.property SET owner_type_code = :type WHERE id = :id",
                        new MapSqlParameterSource().addValue("type", type).addValue("id", propertyId)));
    }

    /** Type-specific details for a registered owner: from the incoming party, else from the previous owner row. */
    private static Map<String, Object> ownerDetails(TransactionContext ctx, String name) {
        for (Map<String, Object> party : ctx.side("SIDE_2")) {
            if (name.equals(party.get("name"))) {
                return party;
            }
        }
        for (Map<String, Object> owner : ctx.propertyOwners()) {
            if (name.equals(owner.get("owner_name"))) {
                return owner;
            }
        }
        return Map.of();
    }

    private byte[] deedHash(TransactionContext ctx, List<TokenService.Owner> owners) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("txnRef", ctx.txnRef());
        canonical.put("propertyRef", ctx.propertyRef());
        canonical.put("deedType", ctx.deedTypeCode());
        canonical.put("transferScope", ctx.transferScope());
        canonical.put("consideration", ctx.dec("declared_consideration"));
        canonical.put("guidelineValue", ctx.dec("guideline_value"));
        canonical.put("owners", owners);
        return Hashes.sha256(json(canonical));
    }

    public Map<String, Object> result(String txnRef) {
        CurrentUser user = CurrentUser.require();
        long id = repository.idOf(txnRef, user.stateCode());
        var rows = jdbc.queryForList("""
                SELECT registered_document_no, registration_year, registration_date, registering_sro,
                       registration_status, encode(deed_sha256,'hex') AS deed_sha256, registration_reference,
                       created_at
                  FROM core.registration_result WHERE transaction_id = :id
                """, new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Registration result for " + txnRef);
        }
        return rows.get(0);
    }

    private BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise registration payload", e);
        }
    }
}
