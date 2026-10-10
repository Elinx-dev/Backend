package in.gov.slate.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import in.gov.slate.common.ApiException;

/**
 * Serves the configuration the UI and the workflow engine run on. Nothing here
 * interprets policy: it only resolves the configured rows for a state, deed type
 * and stage.
 */
@Service
public class ConfigService {

    private final NamedParameterJdbcTemplate jdbc;

    public ConfigService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Everything the SPA needs once, at login. */
    public Map<String, Object> bootstrap(String stateCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state(stateCode));
        out.put("modules", modules(stateCode));
        out.put("deedTypes", deedTypes(stateCode));
        out.put("transactionTypes", transactionTypes(stateCode));
        out.put("surveyFees", surveyFees(stateCode));
        out.put("bloodRelations", bloodRelations(stateCode));
        out.put("feeRelationshipCategories", feeRelationshipCategories(stateCode));
        out.put("optionSets", optionSets());
        out.put("relationships", jdbc.queryForList(
                "SELECT code, name, fee_category, sort_order FROM master.relationship ORDER BY sort_order",
                new MapSqlParameterSource()));
        out.put("documentTypes", jdbc.queryForList(
                "SELECT code, name, applies_to, allowed_mime, max_size_mb FROM master.document_type ORDER BY code",
                new MapSqlParameterSource()));
        out.put("jurisdictions", jdbc.queryForList("""
                SELECT district_code, district_name, taluk_code, taluk_name, village_code, village_name,
                       sro_code, sro_name
                  FROM master.jurisdiction WHERE state_code = :stateCode
                 ORDER BY district_name, taluk_name, village_name
                """, new MapSqlParameterSource("stateCode", stateCode)));
        out.put("featureFlags", featureFlags());
        return out;
    }

    public Map<String, Object> state(String stateCode) {
        var rows = jdbc.queryForList("""
                SELECT state_code, state_name, extent_units, default_extent_unit, numeric_state_id
                  FROM cfg.state WHERE state_code = :stateCode
                """, new MapSqlParameterSource("stateCode", stateCode));
        if (rows.isEmpty()) {
            throw ApiException.notFound("State " + stateCode);
        }
        return rows.get(0);
    }

    public Map<String, String> modules(String stateCode) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT DISTINCT ON (module) module, mode
                  FROM cfg.state_module_config
                 WHERE state_code = :stateCode AND effective_from <= current_date
                   AND (effective_to IS NULL OR effective_to >= current_date)
                 ORDER BY module, effective_from DESC
                """, new MapSqlParameterSource("stateCode", stateCode))
                .forEach(r -> out.put((String) r.get("module"), (String) r.get("mode")));
        return out;
    }

    public String moduleMode(String stateCode, String module) {
        return modules(stateCode).getOrDefault(module, "DISABLED");
    }

    public List<Map<String, Object>> deedTypes(String stateCode) {
        return jdbc.queryForList("""
                SELECT code, name, workflow_family, survey_rule, side1_role, side2_role,
                       witness_required, min_witness_count, requires_relationship_category
                  FROM master.deed_type
                 WHERE state_code = :stateCode AND effective_from <= current_date
                   AND (effective_to IS NULL OR effective_to >= current_date)
                 ORDER BY code
                """, new MapSqlParameterSource("stateCode", stateCode));
    }

    /** Transaction Type Master rows for the state; a state row overrides the '*' default. */
    public List<Map<String, Object>> transactionTypes(String stateCode) {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (code) code, name, first_party_label, second_party_label,
                       subdivision_allowed, individuals_only, blood_relation_required, display_order, status,
                       default_relationship_category,
                       EXISTS (SELECT 1 FROM master.fee_master f
                                WHERE f.state_code = :stateCode AND f.deed_type_code = t.code
                                  AND f.valuation_basis = 'SCHEDULE_VALUE' AND f.status = 'ACTIVE'
                                  AND f.effective_from <= current_date
                                  AND (f.effective_to IS NULL OR f.effective_to >= current_date)) AS schedule_valuation
                  FROM master.transaction_type t
                 WHERE state_code IN (:stateCode, '*') AND status = 'ACTIVE'
                 ORDER BY code, (state_code = '*')
                """, new MapSqlParameterSource("stateCode", stateCode)).stream()
                .sorted(Comparator.comparingInt(r -> ((Number) r.get("display_order")).intValue()))
                .toList();
    }

    /** The active transaction type, or null when the code is not in the Transaction Type Master. */
    public Map<String, Object> transactionType(String stateCode, String code) {
        return transactionTypes(stateCode).stream()
                .filter(t -> code != null && code.equals(t.get("code")))
                .findFirst().orElse(null);
    }

    public boolean isConfiguredTransactionType(String stateCode, String code) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM master.transaction_type
                 WHERE state_code IN (:stateCode, '*') AND code = :code
                """, new MapSqlParameterSource().addValue("stateCode", stateCode).addValue("code", code),
                Integer.class);
        return count != null && count > 0;
    }

    public List<Map<String, Object>> surveyFees(String stateCode) {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (location_type) location_type, location_name, fee, land_type_code,
                       display_order, status
                  FROM master.survey_fee
                 WHERE state_code IN (:stateCode, '*') AND status = 'ACTIVE'
                 ORDER BY location_type, (state_code = '*')
                """, new MapSqlParameterSource("stateCode", stateCode)).stream()
                .sorted(Comparator.comparingInt(r -> ((Number) r.get("display_order")).intValue()))
                .toList();
    }

    /** The active Survey Fee Master row for a location type, or null. */
    public Map<String, Object> surveyFee(String stateCode, String locationType) {
        return surveyFees(stateCode).stream()
                .filter(f -> locationType != null && locationType.equals(f.get("location_type")))
                .findFirst().orElse(null);
    }

    /** Fee relationship categories; transaction_types is a comma-separated list of transaction type codes. */
    public List<Map<String, Object>> feeRelationshipCategories(String stateCode) {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (code) code, label, array_to_string(transaction_types, ',') AS transaction_types,
                       display_order
                  FROM master.fee_relationship_category
                 WHERE state_code IN (:stateCode, '*') AND status = 'ACTIVE'
                 ORDER BY code, (state_code = '*')
                """, new MapSqlParameterSource("stateCode", stateCode)).stream()
                .sorted(Comparator.comparingInt(r -> ((Number) r.get("display_order")).intValue()))
                .toList();
    }

    public boolean isFeeRelationshipCategory(String stateCode, String transactionType, String code) {
        return feeRelationshipCategories(stateCode).stream()
                .filter(c -> code.equals(c.get("code")))
                .anyMatch(c -> List.of(((String) c.get("transaction_types")).split(",")).contains(transactionType));
    }

    public List<Map<String, Object>> bloodRelations(String stateCode) {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (code) code, relationship_name, display_order, status
                  FROM master.blood_relation
                 WHERE state_code IN (:stateCode, '*') AND status = 'ACTIVE'
                 ORDER BY code, (state_code = '*')
                """, new MapSqlParameterSource("stateCode", stateCode)).stream()
                .sorted(Comparator.comparingInt(r -> ((Number) r.get("display_order")).intValue()))
                .toList();
    }

    public boolean isActiveBloodRelation(String stateCode, String code) {
        return code != null && bloodRelations(stateCode).stream().anyMatch(r -> code.equals(r.get("code")));
    }

    /** Active users holding the SURVEYOR role in the state. */
    public List<Map<String, Object>> surveyors(String stateCode) {
        return jdbc.queryForList("""
                SELECT u.id, u.username, u.full_name, u.designation,
                       string_agg(DISTINCT coalesce(j.village_code, j.taluk_code, j.district_code), ', ') AS jurisdiction
                  FROM sec.user u
                  JOIN sec.user_role ur ON ur.user_id = u.id
                  JOIN sec.role r ON r.id = ur.role_id AND r.code = 'SURVEYOR'
                  LEFT JOIN sec.user_jurisdiction j ON j.user_id = u.id
                 WHERE u.state_code = :stateCode AND u.status = 'ACTIVE'
                 GROUP BY u.id, u.username, u.full_name, u.designation
                 ORDER BY u.full_name
                """, new MapSqlParameterSource("stateCode", stateCode));
    }

    public Map<String, Object> deedType(String stateCode, String code) {
        var rows = jdbc.queryForList("""
                SELECT d.code, coalesce(tt.name, d.name) AS name, d.workflow_family, d.survey_rule,
                       coalesce(upper(replace(tt.first_party_label, ' ', '_')), d.side1_role) AS side1_role,
                       coalesce(upper(replace(tt.second_party_label, ' ', '_')), d.side2_role) AS side2_role,
                       d.witness_required, d.min_witness_count, d.requires_relationship_category,
                       tt.first_party_label, tt.second_party_label, tt.subdivision_allowed,
                       tt.individuals_only, tt.blood_relation_required, tt.owner_side
                  FROM master.deed_type d
                  LEFT JOIN LATERAL (
                       SELECT * FROM master.transaction_type t
                        WHERE t.code = d.code AND t.state_code IN (d.state_code, '*')
                        ORDER BY (t.state_code = '*') LIMIT 1) tt ON true
                 WHERE d.state_code = :stateCode AND d.code = :code
                """, new MapSqlParameterSource().addValue("stateCode", stateCode).addValue("code", code));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Deed type " + code + " for state " + stateCode);
        }
        return rows.get(0);
    }

    @Cacheable("optionSets")
    public Map<String, List<Map<String, Object>>> optionSets() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT option_set_code, value_code, label, sort_order, attributes
                  FROM cfg.option_value WHERE active ORDER BY option_set_code, sort_order
                """, new MapSqlParameterSource())
                .forEach(r -> {
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("code", r.get("value_code"));
                    option.put("label", r.get("label"));
                    if (r.get("attributes") != null) {
                        option.put("attributes", r.get("attributes"));
                    }
                    out.computeIfAbsent((String) r.get("option_set_code"), k -> new java.util.ArrayList<>()).add(option);
                });
        return out;
    }

    public Map<String, Boolean> featureFlags() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        jdbc.queryForList("SELECT flag_code, enabled FROM cfg.feature_flag", new MapSqlParameterSource())
                .forEach(r -> out.put((String) r.get("flag_code"), (Boolean) r.get("enabled")));
        return out;
    }

          public Map<String, Object> adminSnapshot(String stateCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state(stateCode));
        out.put("modules", jdbc.queryForList("""
          SELECT id, module, enabled, mode, owner_department, sla_days, notes,
                 effective_from, effective_to
            FROM cfg.state_module_config
           WHERE state_code = :stateCode AND effective_from <= current_date
             AND (effective_to IS NULL OR effective_to >= current_date)
           ORDER BY module
          """, new MapSqlParameterSource("stateCode", stateCode)));
        out.put("featureFlags", jdbc.queryForList("""
          SELECT id, flag_code, enabled, description
            FROM cfg.feature_flag
           WHERE state_code IN (:stateCode, '*') ORDER BY flag_code
          """, new MapSqlParameterSource("stateCode", stateCode)));
        out.put("workflows", jdbc.queryForList("""
          SELECT id, deed_type_code, workflow_code, version, status,
                 effective_from, effective_to, published_at
            FROM cfg.workflow_definition
           WHERE state_code = :stateCode
           ORDER BY deed_type_code, version DESC
          """, new MapSqlParameterSource("stateCode", stateCode)));
        out.put("ruleEngines", ruleOutcomePolicyView(stateCode));
        out.put("ruleCheckCatalog", RuleOutcomePolicy.catalog());
        return out;
          }

          public void updateModule(String stateCode, String module, boolean enabled, String mode,
                 String ownerDepartment, Integer slaDays, String notes) {
        int changed = jdbc.update("""
          UPDATE cfg.state_module_config
             SET enabled = :enabled, mode = :mode, owner_department = :ownerDepartment,
                 sla_days = :slaDays, notes = :notes
           WHERE state_code = :stateCode AND module = :module
             AND effective_from <= current_date
             AND (effective_to IS NULL OR effective_to >= current_date)
          """, new MapSqlParameterSource().addValue("stateCode", stateCode)
          .addValue("module", module).addValue("enabled", enabled).addValue("mode", mode)
          .addValue("ownerDepartment", ownerDepartment).addValue("slaDays", slaDays)
          .addValue("notes", notes));
        if (changed == 0) throw ApiException.notFound("Module " + module);
          }

          public void updateFeatureFlag(String stateCode, String flagCode, boolean enabled) {
        int changed = jdbc.update("""
          UPDATE cfg.feature_flag SET enabled = :enabled
                 WHERE flag_code = :flagCode
                   AND (state_code = :stateCode OR (state_code = '*' AND NOT EXISTS (
                       SELECT 1 FROM cfg.feature_flag WHERE flag_code = :flagCode AND state_code = :stateCode
                   )))
          """, new MapSqlParameterSource().addValue("stateCode", stateCode)
          .addValue("flagCode", flagCode).addValue("enabled", enabled));
        if (changed == 0) throw ApiException.notFound("Feature flag " + flagCode);
          }

      public void updateWorkflowStatus(String stateCode, long workflowId, String status, long publishedBy) {
        if (!List.of("DRAFT", "PUBLISHED", "RETIRED").contains(status)) {
          throw new IllegalArgumentException("Unsupported workflow status");
        }
        int changed = jdbc.update("""
            UPDATE cfg.workflow_definition
               SET status = :status,
                 published_at = CASE WHEN :status = 'PUBLISHED' THEN now() ELSE published_at END,
                 published_by = CASE WHEN :status = 'PUBLISHED' THEN :publishedBy ELSE published_by END
             WHERE id = :workflowId AND state_code = :stateCode
            """, new MapSqlParameterSource().addValue("stateCode", stateCode)
            .addValue("workflowId", workflowId).addValue("status", status)
            .addValue("publishedBy", publishedBy));
        if (changed == 0) throw ApiException.notFound("Workflow " + workflowId);
      }

    /** The published workflow (stages + transitions) for a deed type. */
    public Map<String, Object> workflow(String stateCode, String deedTypeCode) {
        var params = new MapSqlParameterSource()
                .addValue("stateCode", stateCode)
                .addValue("deedTypeCode", deedTypeCode);
        var defs = jdbc.queryForList("""
                SELECT id, workflow_code, version
                  FROM cfg.workflow_definition
                 WHERE state_code = :stateCode AND deed_type_code = :deedTypeCode AND status = 'PUBLISHED'
                 ORDER BY version DESC LIMIT 1
                """, params);
        if (defs.isEmpty()) {
            throw ApiException.notFound("Published workflow for " + deedTypeCode);
        }
        long workflowId = ((Number) defs.get(0).get("id")).longValue();
        var idParam = new MapSqlParameterSource("workflowId", workflowId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", workflowId);
        out.put("workflowCode", defs.get(0).get("workflow_code"));
        out.put("version", defs.get(0).get("version"));
        out.put("stages", jdbc.queryForList("""
                SELECT seq, stage_code, stage_label, status_on_enter, owner_role, optional, skip_condition, ui_route
                  FROM cfg.workflow_stage WHERE workflow_id = :workflowId ORDER BY seq
                """, idParam));
        out.put("transitions", jdbc.queryForList("""
                SELECT from_status, to_status, action_code, allowed_roles, guard_expr, requires_reason, emits_event
                  FROM cfg.workflow_transition WHERE workflow_id = :workflowId ORDER BY id
                """, idParam));
        return out;
    }

    /** Field configuration for one stage, already merged with the field definitions. */
    public List<Map<String, Object>> fields(String stateCode, String deedTypeCode, String stageCode) {
        return jdbc.queryForList("""
                SELECT fd.entity, fd.field_code, fd.data_type, fd.pii, fd.public_view_safe,
                       fc.visible, fc.required, fc.masked, fc.option_set_code, fc.default_value,
                       fc.min_value, fc.max_value, fc.regex, fc.display_order, fc.ui_group, fc.help_text
                  FROM cfg.field_config fc
                  JOIN cfg.field_definition fd ON fd.id = fc.field_id
                 WHERE (fc.state_code = :stateCode OR fc.state_code = '*')
                   AND (fc.deed_type_code = :deedTypeCode OR fc.deed_type_code = '*')
                   AND fc.stage_code = :stageCode
                   AND fc.effective_from <= current_date
                   AND (fc.effective_to IS NULL OR fc.effective_to >= current_date)
                 ORDER BY fc.ui_group, fc.display_order
                """, new MapSqlParameterSource()
                .addValue("stateCode", stateCode)
                .addValue("deedTypeCode", deedTypeCode)
                .addValue("stageCode", stageCode));
    }

    public List<Map<String, Object>> validationRules(String deedTypeCode, String scope) {
        return jdbc.queryForList("""
                SELECT rule_code, scope, expression, severity, message
                  FROM cfg.validation_rule
                 WHERE (deed_type_code = :deedTypeCode OR deed_type_code = '*')
                   AND (CAST(:scope AS text) IS NULL OR scope = :scope)
                   AND active
                 ORDER BY scope, rule_code
                """, new MapSqlParameterSource()
                .addValue("deedTypeCode", deedTypeCode)
                .addValue("scope", scope));
    }

    public Map<String, Object> ruleEngineConfig(String stateCode, String engine) {
        var rows = jdbc.queryForList("""
                SELECT engine, ec_lookback_years, extent_tolerance_pct, supported_land_types, enabled,
                       blocking_reason_codes, apply_survey_lineage
                  FROM cfg.rule_engine_config WHERE state_code = :stateCode AND engine = :engine
                """, new MapSqlParameterSource().addValue("stateCode", stateCode).addValue("engine", engine));
        return rows.isEmpty() ? Map.of("enabled", false) : rows.get(0);
    }

    /** Per engine: the outcomes allowed to proceed past Rule checks and the reasons that always stop. */
    public Map<String, RuleOutcomePolicy> ruleOutcomePolicies(String stateCode) {
        Map<String, RuleOutcomePolicy> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT engine, allowed_outcomes, blocking_reason_codes FROM cfg.rule_engine_config
                 WHERE state_code = :stateCode ORDER BY engine
                """, new MapSqlParameterSource("stateCode", stateCode), rs -> {
            out.put(rs.getString("engine"), new RuleOutcomePolicy(
                    textSet(rs.getArray("allowed_outcomes")), textSet(rs.getArray("blocking_reason_codes"))));
        });
        return out;
    }

    public boolean ruleResultBlocks(String stateCode, String engine, String outcome, String reasonCode) {
        return ruleOutcomePolicies(stateCode).getOrDefault(engine, RuleOutcomePolicy.DEFAULT)
                .blocks(outcome, reasonCode);
    }

    public List<Map<String, Object>> ruleOutcomePolicyView(String stateCode) {
        return ruleOutcomePolicies(stateCode).entrySet().stream()
                .map(e -> e.getValue().view(e.getKey())).toList();
    }

    public RuleOutcomePolicy updateRuleOutcomePolicy(String stateCode, String engine,
                                                     List<String> allowedOutcomes, List<String> blockingReasonCodes) {
        RuleOutcomePolicy policy = RuleOutcomePolicy.validated(allowedOutcomes, blockingReasonCodes);
        int changed = jdbc.update("""
                UPDATE cfg.rule_engine_config
                   SET allowed_outcomes = string_to_array(:allowed, ','),
                       blocking_reason_codes = string_to_array(:blocking, ',')
                 WHERE state_code = :stateCode AND engine = :engine
                """, new MapSqlParameterSource().addValue("stateCode", stateCode).addValue("engine", engine)
                .addValue("allowed", String.join(",", policy.allowedOutcomes()))
                .addValue("blocking", String.join(",", policy.blockingReasonCodes())));
        if (changed == 0) throw ApiException.notFound("Rule engine " + engine);
        return policy;
    }

    private static Set<String> textSet(java.sql.Array array) throws java.sql.SQLException {
        Set<String> out = new LinkedHashSet<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                out.add(String.valueOf(value));
            }
        }
        return out;
    }

    /** Active EC classification keywords grouped by category, lower-cased. */
    public Map<String, List<String>> ecClassificationKeywords() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT category, keyword FROM cfg.ec_classification_keyword WHERE active ORDER BY category, keyword
                """, new MapSqlParameterSource())
                .forEach(r -> out.computeIfAbsent((String) r.get("category"), k -> new ArrayList<>())
                        .add(((String) r.get("keyword")).toLowerCase(Locale.ROOT)));
        return out;
    }

    public boolean rulesAreBlocking() {
        return Boolean.TRUE.equals(featureFlags().get("RULES_BLOCKING"));
    }
}
