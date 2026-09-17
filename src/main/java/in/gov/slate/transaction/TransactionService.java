package in.gov.slate.transaction;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.common.Hashes;
import in.gov.slate.common.NumberingService;
import in.gov.slate.config.ConfigService;
import jakarta.validation.constraints.NotBlank;

@Service
public class TransactionService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService config;
    private final TransactionRepository repository;
    private final WorkflowEngine workflow;
    private final ValidationEngine validation;
    private final NumberingService numbering;
    private final AuditService audit;
    private final String aadhaarSalt;
    private final String aadhaarSaltRef;

    public TransactionService(NamedParameterJdbcTemplate jdbc, ConfigService config,
                              TransactionRepository repository, WorkflowEngine workflow,
                              ValidationEngine validation, NumberingService numbering, AuditService audit,
                              @Value("${slate.aadhaar.salt}") String aadhaarSalt,
                              @Value("${slate.aadhaar.salt-ref}") String aadhaarSaltRef) {
        this.jdbc = jdbc;
        this.config = config;
        this.repository = repository;
        this.workflow = workflow;
        this.validation = validation;
        this.numbering = numbering;
        this.audit = audit;
        this.aadhaarSalt = aadhaarSalt;
        this.aadhaarSaltRef = aadhaarSaltRef;
    }

    public record CreateRequest(@NotBlank String propertyRef, @NotBlank String deedTypeCode,
                                String subtype, String transferScope, String sroCode, String remarks) {
    }

    public record DetailsRequest(BigDecimal declaredConsideration, String modeOfConsideration,
                                 BigDecimal extentOrShareTransferred, String extentUnit,
                                 String relationshipCategory, String basisOfSettlement,
                                 BigDecimal shareBeingReleased, Integer resultingSubparcelCount,
                                 BigDecimal guidelineValue, String guidelineValueReference, String remarks) {
    }

    public record PartyInput(@NotBlank String side, String role, @NotBlank String partyType, @NotBlank String name,
                             String aadhaarNumber, String kartaName, String kartaAadhaarNumber, String pan,
                             String address, String relationshipCode, BigDecimal existingSharePct,
                             BigDecimal shareTransferredPct, BigDecimal extentTransferred,
                             BigDecimal resultingSharePct, String authorityPoaReference) {
    }

    public record WitnessInput(@NotBlank String name, String address, String idProofType, String idProofRef) {
    }

    @Transactional
    public Map<String, Object> create(CreateRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_CREATE");

        var propertyRows = jdbc.queryForList(
                "SELECT id, property_ref, sro_code, district_code FROM core.property WHERE property_ref = :ref AND state_code = :state",
                new MapSqlParameterSource().addValue("ref", req.propertyRef()).addValue("state", user.stateCode()));
        if (propertyRows.isEmpty()) {
            throw ApiException.notFound("Property " + req.propertyRef());
        }
        Map<String, Object> property = propertyRows.get(0);

        Map<String, Object> deedType = config.deedType(user.stateCode(), req.deedTypeCode());
        String transferScope = resolveTransferScope(req.deedTypeCode(), req.subtype(), req.transferScope());
        boolean surveyRequired = SurveyRequirement.derive((String) deedType.get("survey_rule"), transferScope);

        Map<String, Object> wf = config.workflow(user.stateCode(), req.deedTypeCode());
        String txnRef = numbering.next(user.stateCode(), "TXN_REF", (String) property.get("district_code"));

        var params = new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("txnRef", txnRef)
                .addValue("propertyId", property.get("id"))
                .addValue("deedTypeCode", req.deedTypeCode())
                .addValue("subtype", req.subtype())
                .addValue("workflowId", wf.get("workflowId"))
                .addValue("configVersion", wf.get("version"))
                .addValue("transferScope", transferScope)
                .addValue("surveyRequired", surveyRequired)
                .addValue("sroCode", req.sroCode() != null ? req.sroCode() : property.get("sro_code"))
                .addValue("remarks", req.remarks())
                .addValue("idempotencyKey", UUID.randomUUID())
                .addValue("initiatedBy", user.id());

        jdbc.update("""
                INSERT INTO core.transaction (state_code, txn_ref, property_id, deed_type_code, subtype,
                    workflow_id, config_version, transfer_scope, survey_required, status, current_stage_code,
                    sro_code, remarks, idempotency_key, initiated_by)
                VALUES (:stateCode, :txnRef, :propertyId, :deedTypeCode, :subtype,
                    :workflowId, :configVersion, :transferScope, :surveyRequired, 'DRAFT', 'PROPERTY_IDENTIFICATION',
                    :sroCode, :remarks, :idempotencyKey, :initiatedBy)
                """, params);

        audit.record("TRANSACTION_CREATED", "TRANSACTION", txnRef, txnRef, (String) property.get("property_ref"),
                Map.of("deedType", req.deedTypeCode(), "surveyRequired", surveyRequired), null);
        return detail(txnRef);
    }

    /**
     * Sale carries its scope in the subtype; gift and settlement carry it explicitly;
     * the remaining types have exactly one possible scope.
     */
    private String resolveTransferScope(String deedTypeCode, String subtype, String explicitScope) {
        return switch (deedTypeCode) {
            case "SALE_FULL" -> "FULL_PROPERTY";
            case "SALE_UNDIVIDED_SHARE" -> "UNDIVIDED_SHARE";
            case "SALE_PARTIAL_SUBDIVISION", "PARTITION" -> SurveyRequirement.PHYSICAL_PARTIAL;
            case "RELEASE_RELINQUISHMENT" -> "UNDIVIDED_SHARE";
            case "GIFT", "SETTLEMENT" -> {
                if (explicitScope == null || explicitScope.isBlank()) {
                    throw ApiException.badRequest("transferScope is required for " + deedTypeCode);
                }
                yield explicitScope;
            }
            default -> explicitScope;
        };
    }

    @Transactional
    public Map<String, Object> updateDetails(String txnRef, DetailsRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_EDIT");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        requireEditable(ctx);

        jdbc.update("""
                UPDATE core.transaction SET
                    declared_consideration = :consideration,
                    mode_of_consideration = :mode,
                    extent_or_share_transferred = :extent,
                    extent_unit = :extentUnit,
                    relationship_category = :relationshipCategory,
                    basis_of_settlement = :basis,
                    share_being_released = :shareReleased,
                    resulting_subparcel_count = :subparcels,
                    guideline_value = :guidelineValue,
                    guideline_value_reference = :guidelineValueRef,
                    remarks = coalesce(:remarks, remarks)
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", ctx.id())
                .addValue("consideration", req.declaredConsideration())
                .addValue("mode", req.modeOfConsideration())
                .addValue("extent", req.extentOrShareTransferred())
                .addValue("extentUnit", req.extentUnit())
                .addValue("relationshipCategory", req.relationshipCategory())
                .addValue("basis", req.basisOfSettlement())
                .addValue("shareReleased", req.shareBeingReleased())
                .addValue("subparcels", req.resultingSubparcelCount())
                .addValue("guidelineValue", req.guidelineValue())
                .addValue("guidelineValueRef", req.guidelineValueReference())
                .addValue("remarks", req.remarks()));

        audit.record("TRANSACTION_DETAILS_UPDATED", "TRANSACTION", String.valueOf(ctx.id()), txnRef,
                ctx.propertyRef(), Map.of("guidelineValue", String.valueOf(req.guidelineValue())), null);
        return detail(txnRef);
    }

    @Transactional
    public Map<String, Object> saveParties(String txnRef, List<PartyInput> parties) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_EDIT");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        requireEditable(ctx);
        if (!ctx.consents().isEmpty()) {
            throw ApiException.conflict("Parties cannot be replaced after consent has been requested");
        }

        jdbc.update("DELETE FROM core.transaction_party WHERE transaction_id = :id",
                new MapSqlParameterSource("id", ctx.id()));

        Map<String, Integer> seqBySide = new LinkedHashMap<>();
        for (PartyInput p : parties) {
            validateAadhaar(p.aadhaarNumber());
            int seq = seqBySide.merge(p.side(), 1, Integer::sum);
            String defaultRole = "SIDE_1".equals(p.side())
                    ? (String) ctx.deedType().get("side1_role")
                    : (String) ctx.deedType().get("side2_role");
            jdbc.update("""
                    INSERT INTO core.transaction_party (transaction_id, side, role, seq, party_type, name,
                        aadhaar_hash, aadhaar_last4, aadhaar_salt_ref, karta_name, karta_aadhaar_hash, pan, address,
                        relationship_code, existing_share_pct, share_transferred_pct, extent_transferred,
                        resulting_share_pct, authority_poa_reference)
                    VALUES (:txnId, :side, :role, :seq, :partyType, :name,
                        :aadhaarHash, :last4, :saltRef, :kartaName, :kartaHash, :pan, :address,
                        :relationshipCode, :existingShare, :shareTransferred, :extentTransferred,
                        :resultingShare, :authorityRef)
                    """, new MapSqlParameterSource()
                    .addValue("txnId", ctx.id())
                    .addValue("side", p.side())
                    .addValue("role", p.role() != null ? p.role() : defaultRole)
                    .addValue("seq", seq)
                    .addValue("partyType", p.partyType())
                    .addValue("name", p.name())
                    .addValue("aadhaarHash", hashAadhaar(p.aadhaarNumber()))
                    .addValue("last4", last4(p.aadhaarNumber()))
                    .addValue("saltRef", p.aadhaarNumber() == null ? null : aadhaarSaltRef)
                    .addValue("kartaName", p.kartaName())
                    .addValue("kartaHash", hashAadhaar(p.kartaAadhaarNumber()))
                    .addValue("pan", p.pan())
                    .addValue("address", p.address())
                    .addValue("relationshipCode", p.relationshipCode())
                    .addValue("existingShare", p.existingSharePct())
                    .addValue("shareTransferred", p.shareTransferredPct())
                    .addValue("extentTransferred", p.extentTransferred())
                    .addValue("resultingShare", p.resultingSharePct())
                    .addValue("authorityRef", p.authorityPoaReference()));
        }

        TransactionContext updated = repository.load(txnRef, user.stateCode());
        validation.evaluate(updated, "PARTY", true);
        audit.record("PARTIES_SAVED", "TRANSACTION", String.valueOf(ctx.id()), txnRef, ctx.propertyRef(),
                Map.of("partyCount", parties.size()), null);
        return detail(txnRef);
    }

    @Transactional
    public Map<String, Object> saveWitnesses(String txnRef, List<WitnessInput> witnesses) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_EDIT");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        requireEditable(ctx);
        jdbc.update("DELETE FROM core.witness WHERE transaction_id = :id",
                new MapSqlParameterSource("id", ctx.id()));
        int seq = 0;
        for (WitnessInput w : witnesses) {
            seq++;
            jdbc.update("""
                    INSERT INTO core.witness (transaction_id, seq, name, address, id_proof_type, id_proof_ref)
                    VALUES (:txnId, :seq, :name, :address, :type, :ref)
                    """, new MapSqlParameterSource()
                    .addValue("txnId", ctx.id())
                    .addValue("seq", seq)
                    .addValue("name", w.name())
                    .addValue("address", w.address())
                    .addValue("type", w.idProofType())
                    .addValue("ref", w.idProofRef()));
        }
        audit.record("WITNESSES_SAVED", "TRANSACTION", String.valueOf(ctx.id()), txnRef, ctx.propertyRef(),
                Map.of("witnessCount", witnesses.size()), null);
        return detail(txnRef);
    }

    @Transactional
    public Map<String, Object> transition(String txnRef, String actionCode, String reason) {
        CurrentUser user = CurrentUser.require();
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        workflow.apply(ctx, actionCode, reason, user);
        return detail(txnRef);
    }

    public Map<String, Object> detail(String txnRef) {
        CurrentUser user = CurrentUser.require();
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        Map<String, Object> out = new LinkedHashMap<>(ctx.transaction());
        out.put("property", ctx.property());
        out.put("deedType", ctx.deedType());
        out.put("parties", ctx.parties());
        out.put("witnesses", ctx.witnesses());
        out.put("consents", ctx.consents());
        out.put("ruleCheckResults", ctx.ruleResults());
        out.put("feeCalculation", ctx.feeCalculation());
        out.put("payments", ctx.payments());
        out.put("registeredOwners", ctx.propertyOwners());
        out.put("surveyParcels", ctx.surveyParcels());
        out.put("availableActions", workflow.availableActions(ctx, user));
        out.put("validation", validation.evaluate(ctx, "TRANSACTION", false));
        out.put("stages", config.workflow(user.stateCode(), ctx.deedTypeCode()).get("stages"));
        out.put("registrationResult", jdbc.queryForList(
                "SELECT * FROM core.registration_result WHERE transaction_id = :id",
                new MapSqlParameterSource("id", ctx.id())));
        out.put("mutation", jdbc.queryForList(
                "SELECT * FROM revenue.proposed_mutation WHERE transaction_id = :id",
                new MapSqlParameterSource("id", ctx.id())));
        return out;
    }

    public List<Map<String, Object>> queue(String status, String stage, String sroCode, int limit) {
        CurrentUser user = CurrentUser.require();
        return jdbc.queryForList("""
                SELECT * FROM rpt.transaction_queue_view
                 WHERE state_code = :stateCode
                   AND (CAST(:status AS text) IS NULL OR status = :status)
                   AND (CAST(:stage AS text) IS NULL OR current_stage_code = :stage)
                   AND (CAST(:sroCode AS text) IS NULL OR sro_code = :sroCode)
                 ORDER BY initiated_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("stateCode", user.stateCode())
                .addValue("status", blankToNull(status))
                .addValue("stage", blankToNull(stage))
                .addValue("sroCode", blankToNull(sroCode))
                .addValue("limit", limit));
    }

    private void requireEditable(TransactionContext ctx) {
        List<String> editable = List.of("DRAFT", "CONSENT_PENDING", "RULE_CHECK_PENDING", "EXCEPTION",
                "FEE_PAYMENT_PENDING");
        if (!editable.contains(ctx.status())) {
            throw ApiException.conflict("Transaction " + ctx.txnRef() + " is " + ctx.status() + " and cannot be edited");
        }
    }

    private void validateAadhaar(String aadhaar) {
        if (aadhaar == null || aadhaar.isBlank()) {
            return;
        }
        if (!aadhaar.matches("\\d{12}")) {
            throw ApiException.badRequest("Aadhaar number must be exactly 12 digits");
        }
    }

    private byte[] hashAadhaar(String aadhaar) {
        return (aadhaar == null || aadhaar.isBlank()) ? null : Hashes.aadhaarHash(aadhaar, aadhaarSalt);
    }

    private String last4(String aadhaar) {
        return (aadhaar == null || aadhaar.length() < 4) ? null : aadhaar.substring(aadhaar.length() - 4);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
