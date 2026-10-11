package in.gov.slate.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.WorkflowEngine;

/**
 * Guideline value and fee computation. Every figure is derived from the
 * versioned fee master and guideline value tables, and the inputs used are
 * stored alongside the result so a calculation can be re-explained later.
 */
@Service
public class FeeService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final WorkflowEngine workflow;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public FeeService(NamedParameterJdbcTemplate jdbc, TransactionRepository repository, WorkflowEngine workflow,
                      AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.workflow = workflow;
        this.audit = audit;
        this.mapper = mapper;
    }

    public record FeeInput(Integer pageCount) {
    }

    public Map<String, Object> guidelineValue(String txnRef) {
        CurrentUser user = CurrentUser.require();
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        return guidelineValueOf(ctx);
    }

    private Map<String, Object> guidelineValueOf(TransactionContext ctx) {
        Map<String, Object> property = ctx.property();
        var rows = jdbc.queryForList("""
                SELECT rate_per_unit, unit, notification_reference, street_or_zone
                  FROM master.guideline_value
                 WHERE state_code = :stateCode AND village_code = :village
                   AND (land_type_code IS NULL OR land_type_code = :landType)
                   AND effective_from <= current_date
                   AND (effective_to IS NULL OR effective_to >= current_date)
                 ORDER BY effective_from DESC LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("stateCode", property.get("state_code"))
                .addValue("village", property.get("village_code"))
                .addValue("landType", property.get("land_type_code")));
        if (rows.isEmpty()) {
            return propertyGuidelineValue(ctx);
        }
        Map<String, Object> rate = rows.get(0);
        BigDecimal ratePerUnit = new BigDecimal(rate.get("rate_per_unit").toString());
        BigDecimal extent = transferredExtent(ctx);
        BigDecimal amount = ratePerUnit.multiply(extent).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ratePerUnit", ratePerUnit);
        out.put("unit", rate.get("unit"));
        out.put("zone", rate.get("street_or_zone"));
        out.put("notificationReference", rate.get("notification_reference"));
        out.put("extentConsidered", extent);
        out.put("guidelineValue", amount);
        return out;
    }

    private Map<String, Object> propertyGuidelineValue(TransactionContext ctx) {
        Map<String, Object> property = ctx.property();
        BigDecimal ratePerUnit = decimal(property.get("guideline_value"));
        if (ratePerUnit == null || ratePerUnit.signum() <= 0) {
            throw ApiException.notFound("Guideline value for village " + property.get("village_code"));
        }

        BigDecimal extent = transferredExtent(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ratePerUnit", ratePerUnit);
        out.put("unit", property.get("extent_unit"));
        out.put("zone", property.get("street"));
        out.put("notificationReference", property.get("guideline_value_reference"));
        out.put("extentConsidered", extent);
        out.put("guidelineValue", ratePerUnit.multiply(extent).setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    /** Fees are charged on the extent actually transferred, not always the whole parcel. */
    private BigDecimal transferredExtent(TransactionContext ctx) {
        BigDecimal propertyExtent = new BigDecimal(ctx.property().get("extent_value").toString());
        BigDecimal declaredExtent = ctx.dec("extent_or_share_transferred");
        String scope = ctx.transferScope();
        if ("UNDIVIDED_SHARE".equals(scope)) {
            BigDecimal sharePct = declaredExtent == null ? HUNDRED : declaredExtent;
            return propertyExtent.multiply(sharePct).divide(HUNDRED, 4, RoundingMode.HALF_UP);
        }
        if (declaredExtent != null && declaredExtent.signum() > 0
                && declaredExtent.compareTo(propertyExtent) <= 0
                && !"FULL_PROPERTY".equals(scope)) {
            return declaredExtent;
        }
        return propertyExtent;
    }

    @Transactional
    public Map<String, Object> calculate(String txnRef, Integer pageCount) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("FEE_CALCULATE");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        workflow.requireStatus(ctx, "FEE_PAYMENT_PENDING", "Fee calculation", user);

        BigDecimal consideration = ctx.dec("declared_consideration");
        String relationshipCategory = (String) ctx.transaction().get("relationship_category");
        Map<String, Object> feeMaster = resolveFeeMaster(ctx, relationshipCategory);
        String basis = (String) feeMaster.get("valuation_basis");

        Map<String, Object> guideline = null;
        BigDecimal valuation;
        BigDecimal stampDuty;
        BigDecimal registrationFee;
        List<Map<String, Object>> scheduleLines = new ArrayList<>();
        if ("SCHEDULE_VALUE".equals(basis)) {
            List<Map<String, Object>> schedules = jdbc.queryForList("""
                    SELECT label, schedule_value FROM core.transaction_schedule
                     WHERE transaction_id = :txnId ORDER BY seq
                    """, new MapSqlParameterSource("txnId", ctx.id()));
            if (schedules.isEmpty()) {
                throw ApiException.badRequest("Enter the schedule values on the Transaction details tab; "
                        + ctx.deedTypeCode() + " fees are calculated per schedule");
            }
            valuation = BigDecimal.ZERO;
            stampDuty = BigDecimal.ZERO;
            registrationFee = BigDecimal.ZERO;
            for (Map<String, Object> schedule : schedules) {
                BigDecimal value = decimal(schedule.get("schedule_value"));
                BigDecimal scheduleStamp = stampDuty(value, feeMaster);
                BigDecimal scheduleRegistration = registrationFee(value, feeMaster);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("scheduleLabel", schedule.get("label"));
                line.put("scheduleValue", value);
                line.put("stampDuty", scheduleStamp);
                line.put("registrationFee", scheduleRegistration);
                scheduleLines.add(line);
                valuation = valuation.add(value);
                stampDuty = stampDuty.add(scheduleStamp);
                registrationFee = registrationFee.add(scheduleRegistration);
            }
        } else {
            guideline = guidelineValueOf(ctx);
            BigDecimal guidelineValue = (BigDecimal) guideline.get("guidelineValue");
            valuation = switch (basis) {
                case "HIGHER_OF_BOTH" -> consideration == null ? guidelineValue : consideration.max(guidelineValue);
                case "CONSIDERATION" -> consideration == null ? guidelineValue : consideration;
                default -> guidelineValue;
            };
            stampDuty = stampDuty(valuation, feeMaster);
            registrationFee = registrationFee(valuation, feeMaster);
        }

        BigDecimal tds = BigDecimal.ZERO;
        BigDecimal threshold = decimal(feeMaster.get("tds_threshold"));
        BigDecimal tdsRate = decimal(feeMaster.get("tds_rate"));
        if (tdsEnabled(ctx) && consideration != null && threshold != null && tdsRate != null
                && consideration.compareTo(threshold) > 0) {
            tds = consideration.multiply(tdsRate).setScale(2, RoundingMode.HALF_UP);
        }

        FeeCalculator.OtherCharges other = FeeCalculator.otherCharges(jsonOf(feeMaster.get("other_charges")),
                pageCount);
        BigDecimal otherCharges = other.total();
        BigDecimal surveyFee = surveyFee(ctx);
        BigDecimal total = stampDuty.add(registrationFee).add(tds).add(otherCharges).add(surveyFee);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("guideline", guideline);
        input.put("declaredConsideration", consideration);
        input.put("valuationBasis", basis);
        input.put("relationshipCategory", relationshipCategory);
        input.put("feeMasterId", feeMaster.get("id"));
        input.put("feeMasterVersion", feeMaster.get("version"));
        input.put("gazetteReference", feeMaster.get("gazette_reference"));
        input.put("stampDutyCalcType", feeMaster.get("stamp_duty_calc_type"));
        input.put("registrationFeeCalcType", feeMaster.get("registration_fee_calc_type"));
        input.put("pageCount", pageCount);
        input.put("otherChargeLines", other.lines());
        input.put("scheduleLines", scheduleLines);
        input.put("surveyLocationType", ctx.transaction().get("survey_location_type"));
        input.put("surveyFee", surveyFee);

        Long calculationId = jdbc.queryForObject("""
                INSERT INTO core.fee_calculation (transaction_id, fee_master_id, fee_master_version,
                    valuation_basis_used, valuation_amount, stamp_duty, registration_fee, tds_amount,
                    other_charges, survey_fee, total_payable, page_count, calculation_input_json)
                VALUES (:txnId, :feeMasterId, :version, :basis, :valuation, :stampDuty, :registrationFee,
                    :tds, :otherCharges, :surveyFee, :total, :pageCount, cast(:input AS jsonb))
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("txnId", ctx.id())
                .addValue("feeMasterId", feeMaster.get("id"))
                .addValue("version", feeMaster.get("version"))
                .addValue("basis", basis)
                .addValue("valuation", valuation)
                .addValue("stampDuty", stampDuty)
                .addValue("registrationFee", registrationFee)
                .addValue("tds", tds)
                .addValue("otherCharges", otherCharges)
                .addValue("surveyFee", surveyFee)
                .addValue("total", total)
                .addValue("pageCount", pageCount)
                .addValue("input", json(input)), Long.class);

        for (int i = 0; i < scheduleLines.size(); i++) {
            Map<String, Object> line = scheduleLines.get(i);
            jdbc.update("""
                    INSERT INTO core.fee_calculation_line (fee_calculation_id, seq, schedule_label, schedule_value,
                        stamp_duty, registration_fee)
                    VALUES (:calculationId, :seq, :label, :value, :stampDuty, :registrationFee)
                    """, new MapSqlParameterSource()
                    .addValue("calculationId", calculationId)
                    .addValue("seq", i + 1)
                    .addValue("label", line.get("scheduleLabel"))
                    .addValue("value", line.get("scheduleValue"))
                    .addValue("stampDuty", line.get("stampDuty"))
                    .addValue("registrationFee", line.get("registrationFee")));
        }

        if (guideline != null) {
            jdbc.update("""
                    UPDATE core.transaction
                       SET guideline_value = :guidelineValue, guideline_value_reference = :reference
                     WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", ctx.id())
                    .addValue("guidelineValue", guideline.get("guidelineValue"))
                    .addValue("reference", guideline.get("notificationReference")));
        }

        audit.record("FEE_CALCULATED", "TRANSACTION", String.valueOf(ctx.id()), txnRef, ctx.propertyRef(),
                Map.of("valuation", valuation, "total", total), null);

        Map<String, Object> out = new LinkedHashMap<>(input);
        out.put("valuationAmount", valuation);
        out.put("stampDuty", stampDuty);
        out.put("registrationFee", registrationFee);
        out.put("tdsAmount", tds);
        out.put("otherCharges", otherCharges);
        out.put("surveyFee", surveyFee);
        out.put("totalPayable", total);
        return out;
    }

    private BigDecimal stampDuty(BigDecimal value, Map<String, Object> feeMaster) {
        return FeeCalculator.component("stamp duty", value, (String) feeMaster.get("stamp_duty_calc_type"),
                decimal(feeMaster.get("stamp_duty_rate")), decimal(feeMaster.get("stamp_duty_flat")),
                decimal(feeMaster.get("stamp_duty_min")), decimal(feeMaster.get("stamp_duty_max")));
    }

    private BigDecimal registrationFee(BigDecimal value, Map<String, Object> feeMaster) {
        return FeeCalculator.component("registration fee", value,
                (String) feeMaster.get("registration_fee_calc_type"),
                decimal(feeMaster.get("registration_fee_rate")), decimal(feeMaster.get("registration_fee_flat")),
                decimal(feeMaster.get("registration_fee_min")), decimal(feeMaster.get("registration_fee_max")));
    }

    private boolean tdsEnabled(TransactionContext ctx) {
        List<Boolean> flags = jdbc.queryForList(
                "SELECT tds_enabled FROM cfg.fee_rule_config WHERE state_code = :stateCode",
                new MapSqlParameterSource("stateCode", ctx.transaction().get("state_code")), Boolean.class);
        return flags.isEmpty() || Boolean.TRUE.equals(flags.get(0));
    }

    private Map<String, Object> resolveFeeMaster(TransactionContext ctx, String relationshipCategory) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM master.fee_master
                 WHERE state_code = :stateCode AND deed_type_code = :deedType AND status = 'ACTIVE'
                   AND subtype IN ('*', coalesce(:subtype, '*'))
                   AND relationship_category IN ('*', coalesce(:relationshipCategory, '*'))
                   AND effective_from <= current_date
                   AND (effective_to IS NULL OR effective_to >= current_date)
                 ORDER BY (relationship_category <> '*') DESC, (subtype <> '*') DESC, effective_from DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.transaction().get("state_code"))
                .addValue("deedType", ctx.deedTypeCode())
                .addValue("subtype", ctx.transaction().get("subtype"))
                .addValue("relationshipCategory", relationshipCategory));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Active Fee Master row for " + ctx.deedTypeCode()
                    + (relationshipCategory == null ? "" : " / " + relationshipCategory));
        }
        return rows.get(0);
    }

    /** Survey fee from the Survey Fee Master, charged only when the transaction needs a survey. */
    private BigDecimal surveyFee(TransactionContext ctx) {
        String locationType = (String) ctx.transaction().get("survey_location_type");
        if (!ctx.surveyRequired() || locationType == null) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> fees = jdbc.queryForList("""
                SELECT fee FROM master.survey_fee
                 WHERE state_code IN (:stateCode, '*') AND location_type = :locationType AND status = 'ACTIVE'
                 ORDER BY (state_code = '*') LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("stateCode", ctx.transaction().get("state_code"))
                .addValue("locationType", locationType), BigDecimal.class);
        if (fees.isEmpty()) {
            throw ApiException.notFound("Active survey fee for location type " + locationType);
        }
        return fees.get(0).setScale(2, RoundingMode.HALF_UP);
    }

    private JsonNode jsonOf(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readTree(raw.toString());
        } catch (Exception e) {
            throw ApiException.conflict("Fee Master other charges are not valid JSON");
        }
    }

    private BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise fee input", e);
        }
    }
}
