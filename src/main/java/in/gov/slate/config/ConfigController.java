package in.gov.slate.config;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.AuditEvent;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.security.AdminStateScope;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService config;
    private final AuditService audit;
    private final AdminStateScope stateScope;

    public ConfigController(ConfigService config, AuditService audit, AdminStateScope stateScope) {
        this.config = config;
        this.audit = audit;
        this.stateScope = stateScope;
    }

    public record ModuleUpdate(boolean enabled, String mode, String ownerDepartment, Integer slaDays, String notes) {}
    public record FeatureFlagUpdate(boolean enabled) {}
    public record WorkflowUpdate(String status) {}
    public record RuleOutcomePolicyUpdate(List<String> allowedOutcomes, List<String> blockingReasonCodes) {}

    @GetMapping("/admin")
    @PreAuthorize("hasAnyRole('STATE_ADMIN', 'CENTRAL_ADMIN')")
    public Map<String, Object> admin(@RequestParam(required = false) String stateCode) {
        String targetState = stateScope.resolve(stateCode);
        Map<String, Object> snapshot = config.adminSnapshot(targetState);
        snapshot.put("states", stateScope.states());
        return snapshot;
    }

    @PutMapping("/admin/modules/{module}")
    @PreAuthorize("hasAnyRole('STATE_ADMIN', 'CENTRAL_ADMIN')")
    public void updateModule(@PathVariable String module, @RequestParam(required = false) String stateCode,
                             @RequestBody ModuleUpdate request) {
        String targetState = stateScope.resolve(stateCode);
        config.updateModule(targetState, module, request.enabled(), request.mode(),
                request.ownerDepartment(), request.slaDays(), request.notes());
        audit.record(AuditEvent.of("CONFIG_MODULE_UPDATED").stateCode(targetState)
                .category(AuditEvent.CATEGORY_CONFIGURATION)
                .entity("MODULE", module)
                .after(Map.of("enabled", request.enabled(), "mode", String.valueOf(request.mode()),
                        "ownerDepartment", String.valueOf(request.ownerDepartment()),
                        "slaDays", String.valueOf(request.slaDays())))
                .detail(request.notes()));
    }

    @PutMapping("/admin/feature-flags/{flagCode}")
    @PreAuthorize("hasAnyRole('STATE_ADMIN', 'CENTRAL_ADMIN')")
    public void updateFeatureFlag(@PathVariable String flagCode, @RequestParam(required = false) String stateCode,
                                  @RequestBody FeatureFlagUpdate request) {
        String targetState = stateScope.resolve(stateCode);
        config.updateFeatureFlag(targetState, flagCode, request.enabled());
        audit.record(AuditEvent.of("CONFIG_FEATURE_FLAG_UPDATED").stateCode(targetState)
                .category(AuditEvent.CATEGORY_CONFIGURATION)
                .entity("FEATURE_FLAG", flagCode)
                .after(Map.of("enabled", request.enabled()))
                .detail(flagCode + " set to " + request.enabled()));
    }

    @PutMapping("/admin/rule-engines/{engine}")
    @PreAuthorize("hasAnyRole('STATE_ADMIN', 'CENTRAL_ADMIN')")
    public Map<String, Object> updateRuleOutcomePolicy(@PathVariable String engine,
                                                       @RequestParam(required = false) String stateCode,
                                                       @RequestBody RuleOutcomePolicyUpdate request) {
        String targetState = stateScope.resolve(stateCode);
        Map<String, Object> before = config.ruleOutcomePolicies(targetState)
                .getOrDefault(engine, RuleOutcomePolicy.DEFAULT).view(engine);
        RuleOutcomePolicy policy = config.updateRuleOutcomePolicy(targetState, engine,
                request.allowedOutcomes(), request.blockingReasonCodes());
        Map<String, Object> after = policy.view(engine);
        audit.record(AuditEvent.of("CONFIG_RULE_OUTCOME_POLICY_UPDATED").stateCode(targetState)
                .category(AuditEvent.CATEGORY_CONFIGURATION)
                .entity("RULE_ENGINE", engine)
                .before(before)
                .after(after)
                .detail(engine + " outcomes allowed to proceed: " + String.join(", ", policy.allowedOutcomes())));
        return after;
    }

    @PutMapping("/admin/workflows/{workflowId}")
    @PreAuthorize("hasAnyRole('STATE_ADMIN', 'CENTRAL_ADMIN')")
    public void updateWorkflow(@PathVariable long workflowId, @RequestParam(required = false) String stateCode,
                               @RequestBody WorkflowUpdate request) {
        String targetState = stateScope.resolve(stateCode);
        config.updateWorkflowStatus(targetState, workflowId, request.status(),
                CurrentUser.require().id());
        audit.record(AuditEvent.of("CONFIG_WORKFLOW_STATUS_UPDATED").stateCode(targetState)
                .category(AuditEvent.CATEGORY_CONFIGURATION)
                .entity("WORKFLOW", String.valueOf(workflowId))
                .statusChange(null, request.status())
                .detail("Workflow " + workflowId + " set to " + request.status()));
    }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        return config.bootstrap(CurrentUser.require().stateCode());
    }

    @GetMapping("/workflows/{deedTypeCode}")
    public Map<String, Object> workflow(@PathVariable String deedTypeCode,
                                        @RequestParam(required = false) String stateCode) {
        String targetState = stateCode == null ? CurrentUser.require().stateCode() : stateScope.resolve(stateCode);
        return config.workflow(targetState, deedTypeCode);
    }

    @GetMapping("/fields")
    public List<Map<String, Object>> fields(@RequestParam String deedTypeCode, @RequestParam String stageCode) {
        return config.fields(CurrentUser.require().stateCode(), deedTypeCode, stageCode);
    }

    @GetMapping("/validation-rules")
    public List<Map<String, Object>> validationRules(@RequestParam String deedTypeCode,
                                                     @RequestParam(required = false) String scope) {
        return config.validationRules(deedTypeCode, scope);
    }
}
