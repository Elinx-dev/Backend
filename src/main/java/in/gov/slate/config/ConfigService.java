package in.gov.slate.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public Map<String, Object> deedType(String stateCode, String code) {
        var rows = jdbc.queryForList("""
                SELECT code, name, workflow_family, survey_rule, side1_role, side2_role,
                       witness_required, min_witness_count, requires_relationship_category
                  FROM master.deed_type WHERE state_code = :stateCode AND code = :code
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
                SELECT option_set_code, value_code, label, sort_order
                  FROM cfg.option_value WHERE active ORDER BY option_set_code, sort_order
                """, new MapSqlParameterSource())
                .forEach(r -> out.computeIfAbsent((String) r.get("option_set_code"), k -> new java.util.ArrayList<>())
                        .add(Map.of("code", r.get("value_code"), "label", r.get("label"))));
        return out;
    }

    public Map<String, Boolean> featureFlags() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        jdbc.queryForList("SELECT flag_code, enabled FROM cfg.feature_flag", new MapSqlParameterSource())
                .forEach(r -> out.put((String) r.get("flag_code"), (Boolean) r.get("enabled")));
        return out;
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
                SELECT engine, ec_lookback_years, extent_tolerance_pct, supported_land_types, enabled
                  FROM cfg.rule_engine_config WHERE state_code = :stateCode AND engine = :engine
                """, new MapSqlParameterSource().addValue("stateCode", stateCode).addValue("engine", engine));
        return rows.isEmpty() ? Map.of("enabled", false) : rows.get(0);
    }

    public boolean rulesAreBlocking() {
        return Boolean.TRUE.equals(featureFlags().get("RULES_BLOCKING"));
    }
}
