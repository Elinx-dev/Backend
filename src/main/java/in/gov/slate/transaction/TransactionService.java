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
import in.gov.slate.property.OwnerType;
import in.gov.slate.property.PropertyService;
import jakarta.validation.constraints.NotBlank;

@Service
public class TransactionService {

    private static final java.util.regex.Pattern AADHAAR = java.util.regex.Pattern.compile("\\d{12}");
    private static final java.util.regex.Pattern PAN = java.util.regex.Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService config;
    private final TransactionRepository repository;
    private final WorkflowEngine workflow;
    private final ValidationEngine validation;
    private final NumberingService numbering;
    private final AuditService audit;
    private final String aadhaarSalt;
    private final String aadhaarSaltRef;

    private final PropertyService properties;

    public TransactionService(NamedParameterJdbcTemplate jdbc, ConfigService config,
                              TransactionRepository repository, WorkflowEngine workflow,
                              ValidationEngine validation, NumberingService numbering, AuditService audit,
                              PropertyService properties,
                              @Value("${slate.aadhaar.salt}") String aadhaarSalt,
                              @Value("${slate.aadhaar.salt-ref}") String aadhaarSaltRef) {
        this.jdbc = jdbc;
        this.config = config;
        this.repository = repository;
        this.workflow = workflow;
        this.validation = validation;
        this.numbering = numbering;
        this.audit = audit;
        this.properties = properties;
        this.aadhaarSalt = aadhaarSalt;
        this.aadhaarSaltRef = aadhaarSaltRef;
    }

    public record CreateRequest(@NotBlank String propertyRef, @NotBlank String deedTypeCode,
                                String subtype, String transferScope, String sroCode, String remarks,
                                BigDecimal declaredConsideration, String modeOfConsideration,
                                BigDecimal extentOrShareTransferred, String extentUnit,
                                String relationshipCategory, String basisOfSettlement,
                                BigDecimal shareBeingReleased, Integer resultingSubparcelCount,
                                BigDecimal guidelineValue, String guidelineValueReference,
                                Boolean subdivisionRequired, Boolean surveyRequiredByParty,
                                Long surveyorUserId, String surveyLocationType) {
    }

    public record DetailsRequest(BigDecimal declaredConsideration, String modeOfConsideration,
                                 BigDecimal extentOrShareTransferred, String extentUnit,
                                 String relationshipCategory, String basisOfSettlement,
                                 BigDecimal shareBeingReleased, Integer resultingSubparcelCount,
                                 BigDecimal guidelineValue, String guidelineValueReference, String remarks) {
    }

    /**
     * A SIDE_1 party is always a current property owner, referenced by {@code propertyOwnerId};
     * its details are read from the property record, not from the request.
     */
    public record PartyInput(@NotBlank String side, String role, String partyType, String ownerTypeCode,
                             String name, String aadhaarNumber, String kartaName, String kartaAadhaarNumber,
                             String pan, String mobile, String address, String registrationNo,
                             PropertyService.RepresentativeInput representative, String relationshipCode,
                             BigDecimal extentTransferred, String authorityPoaReference, Long propertyOwnerId) {
    }

    public record WitnessInput(@NotBlank String name, String address, String idProofType, String idProofRef) {
    }

    @Transactional
    public Map<String, Object> create(CreateRequest req) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_CREATE");

        var propertyRows = jdbc.queryForList(
                "SELECT id, property_ref, sro_code, district_code, owner_type_code FROM core.property WHERE property_ref = :ref AND state_code = :state",
                new MapSqlParameterSource().addValue("ref", req.propertyRef()).addValue("state", user.stateCode()));
        if (propertyRows.isEmpty()) {
            throw ApiException.notFound("Property " + req.propertyRef());
        }
        Map<String, Object> property = propertyRows.get(0);

        Map<String, Object> deedType = config.deedType(user.stateCode(), req.deedTypeCode());
        String transferScope;
        boolean surveyRequired;
        boolean subdivisionRequired = false;
        boolean surveyRequiredByParty = false;
        String relationshipCategory = req.relationshipCategory();
        Long surveyorUserId = null;
        String surveyLocationType = null;
        if (config.isConfiguredTransactionType(user.stateCode(), req.deedTypeCode())) {
            Map<String, Object> transactionType = config.transactionType(user.stateCode(), req.deedTypeCode());
            if (transactionType == null) {
                throw ApiException.badRequest("Transaction type " + req.deedTypeCode() + " is not active");
            }
            var decision = TransactionTypeRules.decide(transactionType, req.subdivisionRequired(),
                    req.surveyRequiredByParty(), req.transferScope(), (String) property.get("owner_type_code"));
            transferScope = decision.transferScope();
            surveyRequired = decision.surveyRequired();
            subdivisionRequired = decision.subdivisionRequired();
            surveyRequiredByParty = decision.surveyRequiredByParty();
            if (Boolean.TRUE.equals(transactionType.get("blood_relation_required"))) {
                relationshipCategory = "FAMILY";
            }
            if (surveyRequired) {
                surveyorUserId = requireSurveyor(user.stateCode(), req.surveyorUserId());
                surveyLocationType = requireSurveyLocation(user.stateCode(), req.surveyLocationType());
            }
        } else {
            transferScope = resolveTransferScope(req.deedTypeCode(), req.subtype(), req.transferScope());
            surveyRequired = SurveyRequirement.derive((String) deedType.get("survey_rule"), transferScope);
        }

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
                .addValue("declaredConsideration", req.declaredConsideration())
                .addValue("modeOfConsideration", req.modeOfConsideration())
                .addValue("extentOrShareTransferred", req.extentOrShareTransferred())
                .addValue("extentUnit", req.extentUnit())
                .addValue("relationshipCategory", relationshipCategory)
                .addValue("basisOfSettlement", req.basisOfSettlement())
                .addValue("shareBeingReleased", req.shareBeingReleased())
                .addValue("resultingSubparcelCount", req.resultingSubparcelCount())
                .addValue("guidelineValue", req.guidelineValue())
                .addValue("guidelineValueReference", req.guidelineValueReference())
                .addValue("idempotencyKey", UUID.randomUUID())
                .addValue("initiatedBy", user.id())
                .addValue("subdivisionRequired", subdivisionRequired)
                .addValue("surveyRequiredByParty", surveyRequiredByParty)
                .addValue("surveyorUserId", surveyorUserId)
                .addValue("surveyLocationType", surveyLocationType);

        jdbc.update("""
                INSERT INTO core.transaction (state_code, txn_ref, property_id, deed_type_code, subtype,
                    workflow_id, config_version, transfer_scope, survey_required, status, current_stage_code,
                    sro_code, remarks, declared_consideration, mode_of_consideration,
                    extent_or_share_transferred, extent_unit, relationship_category, basis_of_settlement,
                    share_being_released, resulting_subparcel_count, guideline_value,
                    guideline_value_reference, idempotency_key, initiated_by,
                    subdivision_required, survey_required_by_party, assigned_surveyor_id, survey_location_type)
                VALUES (:stateCode, :txnRef, :propertyId, :deedTypeCode, :subtype,
                    :workflowId, :configVersion, :transferScope, :surveyRequired, 'DRAFT', 'PROPERTY_IDENTIFICATION',
                    :sroCode, :remarks, :declaredConsideration, :modeOfConsideration,
                    :extentOrShareTransferred, :extentUnit, :relationshipCategory, :basisOfSettlement,
                    :shareBeingReleased, :resultingSubparcelCount, :guidelineValue,
                    :guidelineValueReference, :idempotencyKey, :initiatedBy,
                    :subdivisionRequired, :surveyRequiredByParty, :surveyorUserId, :surveyLocationType)
                """, params);

        audit.record("TRANSACTION_CREATED", "TRANSACTION", txnRef, txnRef, (String) property.get("property_ref"),
                Map.of("deedType", req.deedTypeCode(), "surveyRequired", surveyRequired,
                        "subdivisionRequired", subdivisionRequired, "surveyRequiredByParty", surveyRequiredByParty),
                null);
        return detail(txnRef);
    }

    public List<Map<String, Object>> surveyors() {
        CurrentUser user = CurrentUser.require();
        return config.surveyors(user.stateCode());
    }

    private long requireSurveyor(String stateCode, Long surveyorUserId) {
        if (surveyorUserId == null) {
            throw ApiException.badRequest("Select a surveyor; this transaction needs a survey");
        }
        boolean known = config.surveyors(stateCode).stream()
                .anyMatch(s -> surveyorUserId.equals(((Number) s.get("id")).longValue()));
        if (!known) {
            throw ApiException.badRequest("User " + surveyorUserId + " is not an active surveyor");
        }
        return surveyorUserId;
    }

    private String requireSurveyLocation(String stateCode, String locationType) {
        if (locationType == null || locationType.isBlank()) {
            throw ApiException.badRequest("Select the survey location type so the survey fee can be applied");
        }
        if (config.surveyFee(stateCode, locationType) == null) {
            throw ApiException.badRequest("No active survey fee for location type " + locationType);
        }
        return locationType;
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
        List<PartyInput> resolved = resolveParties(ctx, parties);
        TransactionTypeRules.validateParties(ctx.deedType(), resolved,
                code -> config.isActiveBloodRelation(user.stateCode(), code));
        validateEnteredParties(user.stateCode(), ctx.deedType(), resolved);

        jdbc.update("DELETE FROM core.transaction_party WHERE transaction_id = :id",
                new MapSqlParameterSource("id", ctx.id()));

        Map<String, Integer> seqBySide = new LinkedHashMap<>();
        for (PartyInput p : resolved) {
            OwnerType.Form form = OwnerType.fromCode(p.ownerTypeCode()).map(OwnerType::form).orElse(null);
            PropertyService.RepresentativeInput rep = form == null || form.representativeRole() == null
                    ? null : p.representative();
            String ownAadhaar = twelveDigits(p.aadhaarNumber());
            String repAadhaar = rep == null ? null : twelveDigits(rep.aadhaarNumber());
            // Entities give Aadhaar consent through their representative.
            String aadhaar = ownAadhaar != null ? ownAadhaar : repAadhaar;
            boolean huf = form == OwnerType.Form.HUF && rep != null;
            int seq = seqBySide.merge(p.side(), 1, Integer::sum);
            String defaultRole = "SIDE_1".equals(p.side())
                    ? (String) ctx.deedType().get("side1_role")
                    : (String) ctx.deedType().get("side2_role");
            jdbc.update("""
                    INSERT INTO core.transaction_party (transaction_id, side, role, seq, party_type, owner_type_code,
                        name, aadhaar_hash, aadhaar_last4, aadhaar_salt_ref, karta_name, karta_aadhaar_hash, pan,
                        mobile, address, registration_no, representative_role, representative_name,
                        representative_designation, representative_aadhaar_last4, representative_pan,
                        representative_mobile, relationship_code, extent_transferred, authority_poa_reference,
                        property_owner_id)
                    VALUES (:txnId, :side, :role, :seq, :partyType, :ownerType,
                        :name, :aadhaarHash, :last4, :saltRef, :kartaName, :kartaHash, :pan,
                        :mobile, :address, :registrationNo, :repRole, :repName,
                        :repDesignation, :repLast4, :repPan,
                        :repMobile, :relationshipCode, :extentTransferred, :authorityRef,
                        :propertyOwnerId)
                    """, new MapSqlParameterSource()
                    .addValue("txnId", ctx.id())
                    .addValue("side", p.side())
                    .addValue("role", p.role() != null ? p.role() : defaultRole)
                    .addValue("seq", seq)
                    .addValue("partyType", p.partyType())
                    .addValue("ownerType", p.ownerTypeCode())
                    .addValue("name", p.name())
                    .addValue("aadhaarHash", hashAadhaar(aadhaar))
                    .addValue("last4", last4(aadhaar))
                    .addValue("saltRef", aadhaar == null ? null : aadhaarSaltRef)
                    .addValue("kartaName", huf ? rep.name() : p.kartaName())
                    .addValue("kartaHash", hashAadhaar(huf ? repAadhaar : p.kartaAadhaarNumber()))
                    .addValue("pan", p.pan())
                    .addValue("mobile", form != null && !form.ownerMobile() ? null : p.mobile())
                    .addValue("address", p.address())
                    .addValue("registrationNo", form != null && form.registrationNo() ? p.registrationNo() : null)
                    .addValue("repRole", rep == null ? null : form.representativeRole())
                    .addValue("repName", rep == null ? null : rep.name())
                    .addValue("repDesignation", rep == null || !form.representativeDesignation() ? null : rep.designation())
                    .addValue("repLast4", last4(repAadhaar))
                    .addValue("repPan", rep == null ? null : rep.pan())
                    .addValue("repMobile", rep == null || !form.representativeMobile() ? null : rep.mobile())
                    .addValue("relationshipCode", p.relationshipCode())
                    .addValue("extentTransferred", p.extentTransferred())
                    .addValue("authorityRef", p.authorityPoaReference())
                    .addValue("propertyOwnerId", p.propertyOwnerId()));
        }

        TransactionContext updated = repository.load(txnRef, user.stateCode());
        validation.evaluate(updated, "PARTY", true);
        audit.record("PARTIES_SAVED", "TRANSACTION", String.valueOf(ctx.id()), txnRef, ctx.propertyRef(),
                Map.of("partyCount", resolved.size()), null);
        return detail(txnRef);
    }

    /**
     * First-party rows come from the property's current owners (all of them, or the ones
     * referenced by propertyOwnerId); second-party rows are taken as entered.
     */
    private List<PartyInput> resolveParties(TransactionContext ctx, List<PartyInput> parties) {
        List<Map<String, Object>> owners = jdbc.queryForList("""
                SELECT id, owner_type_code, owner_name, aadhaar_number, pan, mobile, address, registration_no,
                       representative_name, representative_designation, representative_aadhaar,
                       representative_pan, representative_mobile
                  FROM core.property_owner
                 WHERE property_id = :propertyId AND effective_to IS NULL
                 ORDER BY id
                """, new MapSqlParameterSource("propertyId", ctx.transaction().get("property_id")));
        java.util.Set<Long> selected = parties.stream()
                .filter(p -> "SIDE_1".equals(p.side()) && p.propertyOwnerId() != null)
                .map(PartyInput::propertyOwnerId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<Map<String, Object>> chosen = selected.isEmpty() ? owners : owners.stream()
                .filter(o -> selected.contains(((Number) o.get("id")).longValue()))
                .toList();
        if (chosen.size() != selected.size() && !selected.isEmpty()) {
            throw ApiException.badRequest("Selected owner is not a current owner of property " + ctx.propertyRef());
        }
        if (chosen.isEmpty()) {
            throw ApiException.badRequest("Property " + ctx.propertyRef() + " has no recorded owners");
        }
        List<PartyInput> out = new java.util.ArrayList<>();
        for (Map<String, Object> o : chosen) {
            String ownerType = (String) o.get("owner_type_code");
            String repName = (String) o.get("representative_name");
            PropertyService.RepresentativeInput rep = repName == null ? null
                    : new PropertyService.RepresentativeInput(repName, (String) o.get("representative_designation"),
                            (String) o.get("representative_aadhaar"), (String) o.get("representative_pan"),
                            (String) o.get("representative_mobile"));
            out.add(new PartyInput("SIDE_1", null, ownerPartyType(ownerType), ownerType,
                    (String) o.get("owner_name"), (String) o.get("aadhaar_number"), null, null,
                    (String) o.get("pan"), (String) o.get("mobile"), (String) o.get("address"),
                    (String) o.get("registration_no"), rep, null, null, null, ((Number) o.get("id")).longValue()));
        }
        parties.stream().filter(p -> !"SIDE_1".equals(p.side()))
                .map(p -> p.ownerTypeCode() == null ? p : new PartyInput(p.side(), p.role(),
                        ownerPartyType(p.ownerTypeCode()), p.ownerTypeCode(), p.name(), p.aadhaarNumber(),
                        p.kartaName(), p.kartaAadhaarNumber(), p.pan(), p.mobile(), p.address(),
                        p.registrationNo(), p.representative(), p.relationshipCode(), p.extentTransferred(),
                        p.authorityPoaReference(), null))
                .forEach(out::add);
        return out;
    }

    private static String ownerPartyType(String ownerTypeCode) {
        if (ownerTypeCode == null || "INDIVIDUAL".equals(ownerTypeCode) || "SOLE_PROPRIETORSHIP".equals(ownerTypeCode)) {
            return "INDIVIDUAL";
        }
        return "HUF".equals(ownerTypeCode) ? "HUF" : "INSTITUTION";
    }

    /**
     * Incoming parties share one owner type (the buyer type) and carry the same type-specific fields as
     * Mint Property owners. Rows copied from existing property owners are not re-validated.
     */
    private void validateEnteredParties(String stateCode, Map<String, Object> deedType, List<PartyInput> parties) {
        List<PartyInput> entered = parties.stream().filter(p -> p.propertyOwnerId() == null).toList();
        if (entered.isEmpty()) {
            return;
        }
        String label = java.util.Objects.requireNonNullElse((String) deedType.get("second_party_label"), "Buyer");
        java.util.Set<String> types = new java.util.HashSet<>();
        entered.forEach(p -> types.add(p.ownerTypeCode()));
        if (types.contains(null)) {
            throw ApiException.badRequest("Select the " + label.toLowerCase() + " type");
        }
        if (types.size() > 1) {
            throw ApiException.badRequest("All " + label.toLowerCase() + "s must have the same "
                    + label.toLowerCase() + " type");
        }
        String code = types.iterator().next();
        OwnerType type = OwnerType.fromCode(code)
                .orElseThrow(() -> ApiException.badRequest("Unknown " + label.toLowerCase() + " type " + code));
        Boolean multiple = properties.allowsMultipleOwners(stateCode, code);
        if (multiple == null) {
            throw ApiException.badRequest(label + " type " + code + " is not configured");
        }
        if (entered.size() > 1 && !multiple) {
            throw ApiException.badRequest("Only one " + label.toLowerCase() + " can be recorded for type " + code);
        }
        for (int i = 0; i < entered.size(); i++) {
            PartyInput p = entered.get(i);
            PropertyService.validateOwnerFields(type.form(), label + " " + (i + 1) + ": ",
                    new PropertyService.OwnerInput(p.name(), p.aadhaarNumber(), p.pan(), p.mobile(), p.address(),
                            p.registrationNo(), p.representative()));
        }
    }

    private static String twelveDigits(String value) {
        return value != null && AADHAAR.matcher(value).matches() ? value : null;
    }

    @Transactional
    public Map<String, Object> saveWitnesses(String txnRef, List<WitnessInput> witnesses) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TXN_EDIT");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        requireEditable(ctx);
        jdbc.update("UPDATE core.witness SET is_active = 'N' WHERE transaction_id = :id AND is_active = 'Y'",
                new MapSqlParameterSource("id", ctx.id()));
        int seq = 0;
        for (WitnessInput w : witnesses) {
            seq++;
            jdbc.update("""
                    INSERT INTO core.witness (transaction_id, seq, name, address, id_proof_type, id_proof_ref, is_active)
                    VALUES (:txnId, :seq, :name, :address, :type, :ref, 'Y')
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
        Object surveyorId = ctx.transaction().get("assigned_surveyor_id");
        out.put("assignedSurveyor", surveyorId == null ? null : config.surveyors(user.stateCode()).stream()
                .filter(s -> ((Number) s.get("id")).longValue() == ((Number) surveyorId).longValue())
                .findFirst().orElse(null));
        Object locationType = ctx.transaction().get("survey_location_type");
        out.put("surveyFeeConfig", locationType == null ? null
                : config.surveyFee(user.stateCode(), (String) locationType));
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
