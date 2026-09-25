package in.gov.slate.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            throw ApiException.notFound("Guideline value for village " + property.get("village_code"));
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
    public Map<String, Object> calculate(String txnRef) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("FEE_CALCULATE");
        TransactionContext ctx = repository.load(txnRef, user.stateCode());
        workflow.requireStatus(ctx, "FEE_PAYMENT_PENDING", "Fee calculation", user);

        Map<String, Object> guideline = guidelineValueOf(ctx);
        BigDecimal guidelineValue = (BigDecimal) guideline.get("guidelineValue");
        BigDecimal consideration = ctx.dec("declared_consideration");
        String relationshipCategory = (String) ctx.transaction().get("relationship_category");

        Map<String, Object> feeMaster = resolveFeeMaster(ctx, relationshipCategory);
        String basis = (String) feeMaster.get("valuation_basis");
        BigDecimal valuation = switch (basis) {
            case "HIGHER_OF_BOTH" -> consideration == null ? guidelineValue : consideration.max(guidelineValue);
            case "CONSIDERATION" -> consideration == null ? guidelineValue : consideration;
            default -> guidelineValue;
        };

        BigDecimal stampDuty = charge(valuation, feeMaster, "stamp_duty_rate", "stamp_duty_flat",
                "stamp_duty_min", "stamp_duty_max");
        BigDecimal registrationFee = charge(valuation, feeMaster, "registration_fee_rate", "registration_fee_flat",
                "registration_fee_min", "registration_fee_max");

        BigDecimal tds = BigDecimal.ZERO;
        BigDecimal threshold = decimal(feeMaster.get("tds_threshold"));
        BigDecimal tdsRate = decimal(feeMaster.get("tds_rate"));
        if (consideration != null && threshold != null && tdsRate != null
                && consideration.compareTo(threshold) > 0) {
            tds = consideration.multiply(tdsRate).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal otherCharges = otherCharges(feeMaster);
        BigDecimal total = stampDuty.add(registrationFee).add(tds).add(otherCharges);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("guideline", guideline);
        input.put("declaredConsideration", consideration);
        input.put("valuationBasis", basis);
        input.put("relationshipCategory", relationshipCategory);
        input.put("feeMasterId", feeMaster.get("id"));
        input.put("gazetteReference", feeMaster.get("gazette_reference"));

        jdbc.update("""
                INSERT INTO core.fee_calculation (transaction_id, fee_master_id, fee_master_version,
                    valuation_basis_used, valuation_amount, stamp_duty, registration_fee, tds_amount,
                    other_charges, total_payable, calculation_input_json)
                VALUES (:txnId, :feeMasterId, :version, :basis, :valuation, :stampDuty, :registrationFee,
                    :tds, :otherCharges, :total, cast(:input AS jsonb))
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
                .addValue("total", total)
                .addValue("input", json(input)));

        jdbc.update("""
                UPDATE core.transaction
                   SET guideline_value = :guidelineValue, guideline_value_reference = :reference
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", ctx.id())
                .addValue("guidelineValue", guidelineValue)
                .addValue("reference", guideline.get("notificationReference")));

        audit.record("FEE_CALCULATED", "TRANSACTION", String.valueOf(ctx.id()), txnRef, ctx.propertyRef(),
                Map.of("valuation", valuation, "total", total), null);

        Map<String, Object> out = new LinkedHashMap<>(input);
        out.put("valuationAmount", valuation);
        out.put("stampDuty", stampDuty);
        out.put("registrationFee", registrationFee);
        out.put("tdsAmount", tds);
        out.put("otherCharges", otherCharges);
        out.put("totalPayable", total);
        return out;
    }

    private Map<String, Object> resolveFeeMaster(TransactionContext ctx, String relationshipCategory) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM master.fee_master
                 WHERE state_code = :stateCode AND deed_type_code = :deedType
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
            throw ApiException.notFound("Fee master for " + ctx.deedTypeCode()
                    + (relationshipCategory == null ? "" : " / " + relationshipCategory));
        }
        return rows.get(0);
    }

    private BigDecimal charge(BigDecimal valuation, Map<String, Object> feeMaster, String rateKey, String flatKey,
                              String minKey, String maxKey) {
        BigDecimal rate = decimal(feeMaster.get(rateKey));
        BigDecimal flat = decimal(feeMaster.get(flatKey));
        BigDecimal amount = flat != null ? flat
                : (rate == null ? BigDecimal.ZERO : valuation.multiply(rate).setScale(2, RoundingMode.HALF_UP));
        BigDecimal min = decimal(feeMaster.get(minKey));
        BigDecimal max = decimal(feeMaster.get(maxKey));
        if (min != null && amount.compareTo(min) < 0) {
            amount = min;
        }
        if (max != null && amount.compareTo(max) > 0) {
            amount = max;
        }
        return amount;
    }

    private BigDecimal otherCharges(Map<String, Object> feeMaster) {
        Object raw = feeMaster.get("other_charges");
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        try {
            Map<?, ?> charges = mapper.readValue(raw.toString(), Map.class);
            BigDecimal total = BigDecimal.ZERO;
            Object computer = charges.get("computerCharge");
            if (computer != null) {
                total = total.add(new BigDecimal(computer.toString()));
            }
            return total.setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
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
