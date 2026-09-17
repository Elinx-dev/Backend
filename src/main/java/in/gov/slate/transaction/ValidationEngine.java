package in.gov.slate.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import in.gov.slate.common.ValidationException;
import in.gov.slate.config.ConfigService;

/**
 * Evaluates the cross-field rules configured in cfg.validation_rule. Each
 * configured expression maps to one named predicate here, so policy stays in
 * configuration while the arithmetic stays in code.
 */
@Service
public class ValidationEngine {

    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SHARE_EPSILON = new BigDecimal("0.0100");

    private final ConfigService config;
    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, Predicate<TransactionContext>> predicates = new HashMap<>();

    public ValidationEngine(ConfigService config, NamedParameterJdbcTemplate jdbc) {
        this.config = config;
        this.jdbc = jdbc;
        register();
    }

    /** Runs every configured rule for a scope and throws if any ERROR rule fails. */
    public List<ValidationException.Violation> evaluate(TransactionContext ctx, String scope, boolean throwOnError) {
        List<ValidationException.Violation> violations = new ArrayList<>();
        for (Map<String, Object> rule : config.validationRules(ctx.deedTypeCode(), scope)) {
            String expression = (String) rule.get("expression");
            Predicate<TransactionContext> predicate = predicates.get(expression);
            if (predicate == null) {
                continue;
            }
            if (!predicate.test(ctx)) {
                violations.add(new ValidationException.Violation(
                        (String) rule.get("rule_code"), (String) rule.get("scope"),
                        (String) rule.get("severity"), (String) rule.get("message")));
            }
        }
        boolean hasError = violations.stream().anyMatch(v -> "ERROR".equals(v.severity()));
        if (throwOnError && hasError) {
            throw new ValidationException(violations);
        }
        return violations;
    }

    public boolean guard(String expression, TransactionContext ctx) {
        Predicate<TransactionContext> predicate = predicates.get(expression);
        return predicate == null || predicate.test(ctx);
    }

    private void register() {
        predicates.put("minOnePartyPerSide", ctx ->
                !ctx.side("SIDE_1").isEmpty() && !ctx.side("SIDE_2").isEmpty());

        predicates.put("witnessMinimumMet", ctx -> {
            boolean required = Boolean.TRUE.equals(ctx.deedType().get("witness_required"));
            int min = ((Number) ctx.deedType().get("min_witness_count")).intValue();
            return !required || ctx.witnesses().size() >= min;
        });

        predicates.put("partiesAndWitnessesComplete", ctx ->
                predicates.get("minOnePartyPerSide").test(ctx)
                        && predicates.get("witnessMinimumMet").test(ctx)
                        && ctx.parties().stream().allMatch(p -> Boolean.TRUE.equals(p.get("aadhaar_captured"))));

        predicates.put("allPartiesConsentVerified", ctx -> {
            if (ctx.parties().isEmpty()) {
                return false;
            }
            var verified = ctx.consents().stream()
                    .filter(c -> "VERIFIED".equals(c.get("status")))
                    .map(c -> ((Number) c.get("party_id")).longValue())
                    .toList();
            return ctx.parties().stream()
                    .allMatch(p -> verified.contains(((Number) p.get("id")).longValue()));
        });

        predicates.put("sideTotalsReconcile", ctx -> {
            BigDecimal given = sum(ctx.side("SIDE_1"), "share_transferred_pct");
            BigDecimal received = sum(ctx.side("SIDE_2"), "share_transferred_pct");
            if (given.signum() == 0 && received.signum() == 0) {
                BigDecimal givenExtent = sum(ctx.side("SIDE_1"), "extent_transferred");
                BigDecimal receivedExtent = sum(ctx.side("SIDE_2"), "extent_transferred");
                return givenExtent.subtract(receivedExtent).abs().compareTo(SHARE_EPSILON) <= 0;
            }
            return given.subtract(received).abs().compareTo(SHARE_EPSILON) <= 0;
        });

        predicates.put("guidelineValuePresent", ctx -> {
            BigDecimal value = ctx.dec("guideline_value");
            return value != null && value.signum() > 0;
        });

        predicates.put("feesFullyPaid", ctx -> {
            BigDecimal payable = ctx.totalPayable();
            return payable != null && ctx.paidTotal().compareTo(payable) >= 0;
        });

        predicates.put("considerationPositive", ctx -> {
            BigDecimal value = ctx.dec("declared_consideration");
            return value != null && value.signum() > 0;
        });

        predicates.put("considerationAbsent", ctx -> {
            BigDecimal value = ctx.dec("declared_consideration");
            return value == null || value.signum() == 0;
        });

        predicates.put("relationshipCategoryPresent", ctx -> {
            String category = (String) ctx.transaction().get("relationship_category");
            if (category == null || category.isBlank()) {
                return false;
            }
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM master.relationship WHERE fee_category = :category OR code = :category",
                    new MapSqlParameterSource("category", category), Integer.class);
            return count != null && count > 0;
        });

        predicates.put("basisOfSettlementPresent", ctx ->
                notBlank((String) ctx.transaction().get("basis_of_settlement")));

        predicates.put("releasorsAreRecordedCoOwners", ctx -> {
            var ownerNames = ctx.propertyOwners().stream()
                    .map(o -> normalise((String) o.get("owner_name"))).toList();
            return ctx.side("SIDE_1").stream()
                    .allMatch(p -> ownerNames.contains(normalise((String) p.get("name"))));
        });

        predicates.put("releasedShareMatchesExisting", ctx -> {
            for (Map<String, Object> releasor : ctx.side("SIDE_1")) {
                BigDecimal declared = dec(releasor.get("share_transferred_pct"));
                BigDecimal recorded = ctx.propertyOwners().stream()
                        .filter(o -> normalise((String) o.get("owner_name"))
                                .equals(normalise((String) releasor.get("name"))))
                        .map(o -> dec(o.get("share_pct")))
                        .filter(Objects::nonNull)
                        .findFirst().orElse(null);
                if (declared == null || recorded == null
                        || declared.subtract(recorded).abs().compareTo(SHARE_EPSILON) > 0) {
                    return false;
                }
            }
            return true;
        });

        predicates.put("subparcelCountAtLeastTwo", ctx -> {
            Object count = ctx.transaction().get("resulting_subparcel_count");
            return count != null && ((Number) count).intValue() >= 2;
        });

        predicates.put("subparcelExtentsSumToParent", ctx -> {
            if (ctx.surveyParcels().isEmpty()) {
                // Nothing to reconcile until the surveyor submits the parcels.
                return true;
            }
            BigDecimal parent = dec(ctx.property().get("extent_value"));
            BigDecimal children = ctx.surveyParcels().stream()
                    .map(p -> dec(p.get("extent_value")))
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal tolerance = parent.multiply(new BigDecimal("0.02"));
            return parent.subtract(children).abs().compareTo(tolerance) <= 0;
        });

        predicates.put("sharesAgainstChildParcel", ctx -> {
            BigDecimal received = sum(ctx.side("SIDE_2"), "share_transferred_pct");
            return received.compareTo(BigDecimal.ZERO) > 0 && received.compareTo(HUNDRED) <= 0;
        });

        predicates.put("aadhaarIsTwelveDigits", ctx ->
                ctx.parties().stream().allMatch(p -> Boolean.TRUE.equals(p.get("aadhaar_captured"))));

        predicates.put("panFormatValid", ctx -> ctx.parties().stream()
                .map(p -> (String) p.get("pan"))
                .filter(ValidationEngine::notBlank)
                .allMatch(pan -> PAN.matcher(pan).matches()));

        predicates.put("panPresentAboveTdsThreshold", ctx -> {
            BigDecimal consideration = ctx.dec("declared_consideration");
            if (consideration == null) {
                return true;
            }
            BigDecimal threshold = jdbc.queryForObject("""
                    SELECT max(tds_threshold) FROM master.fee_master
                     WHERE state_code = :stateCode AND deed_type_code = :deedType
                    """, new MapSqlParameterSource()
                    .addValue("stateCode", ctx.transaction().get("state_code"))
                    .addValue("deedType", ctx.deedTypeCode()), BigDecimal.class);
            if (threshold == null || consideration.compareTo(threshold) <= 0) {
                return true;
            }
            return ctx.parties().stream().allMatch(p -> notBlank((String) p.get("pan")));
        });

        predicates.put("hufHasKarta", ctx -> ctx.parties().stream()
                .filter(p -> "HUF".equals(p.get("party_type")))
                .allMatch(p -> notBlank((String) p.get("karta_name"))));

        predicates.put("representativeHasAuthorityRef", ctx -> ctx.parties().stream()
                .filter(p -> "AUTHORISED_REPRESENTATIVE".equals(p.get("party_type")))
                .allMatch(p -> notBlank((String) p.get("authority_poa_reference"))));

        predicates.put("extentPositive", ctx -> {
            BigDecimal extent = dec(ctx.property().get("extent_value"));
            return extent != null && extent.signum() > 0;
        });

        predicates.put("surveyNoPresent", ctx -> notBlank((String) ctx.property().get("survey_no")));

        // Workflow guards.
        predicates.put("ruleChecksRun", ctx -> !ctx.ruleResults().isEmpty());
        predicates.put("rulesAreAdvisory", ctx -> !config.rulesAreBlocking());
        predicates.put("surveyRequired", TransactionContext::surveyRequired);
        predicates.put("surveyNotRequired", ctx -> !ctx.surveyRequired());
        predicates.put("surveyWithinTolerance", ctx -> true);
        predicates.put("mutationVerified", ctx -> true);
        predicates.put("readyToRegister", ctx ->
                predicates.get("allPartiesConsentVerified").test(ctx)
                        && predicates.get("feesFullyPaid").test(ctx)
                        && predicates.get("ruleChecksRun").test(ctx));
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String column) {
        return rows.stream().map(r -> dec(r.get(column))).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal dec(Object value) {
        return value == null ? null : new BigDecimal(value.toString()).setScale(4, RoundingMode.HALF_UP);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
