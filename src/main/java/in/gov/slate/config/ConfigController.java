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

import in.gov.slate.common.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService config;

    public ConfigController(ConfigService config) {
        this.config = config;
    }

    public record ModuleUpdate(boolean enabled, String mode, String ownerDepartment, Integer slaDays, String notes) {}
    public record FeatureFlagUpdate(boolean enabled) {}
    public record WorkflowUpdate(String status) {}

    @GetMapping("/admin")
    @PreAuthorize("hasRole('STATE_ADMIN')")
    public Map<String, Object> admin() {
        return config.adminSnapshot(CurrentUser.require().stateCode());
    }

    @PutMapping("/admin/modules/{module}")
    @PreAuthorize("hasRole('STATE_ADMIN')")
    public void updateModule(@PathVariable String module, @RequestBody ModuleUpdate request) {
        config.updateModule(CurrentUser.require().stateCode(), module, request.enabled(), request.mode(),
                request.ownerDepartment(), request.slaDays(), request.notes());
    }

    @PutMapping("/admin/feature-flags/{flagCode}")
    @PreAuthorize("hasRole('STATE_ADMIN')")
    public void updateFeatureFlag(@PathVariable String flagCode, @RequestBody FeatureFlagUpdate request) {
        config.updateFeatureFlag(CurrentUser.require().stateCode(), flagCode, request.enabled());
    }

    @PutMapping("/admin/workflows/{workflowId}")
    @PreAuthorize("hasRole('STATE_ADMIN')")
    public void updateWorkflow(@PathVariable long workflowId, @RequestBody WorkflowUpdate request) {
        config.updateWorkflowStatus(CurrentUser.require().stateCode(), workflowId, request.status(),
                CurrentUser.require().id());
    }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        return config.bootstrap(CurrentUser.require().stateCode());
    }

    @GetMapping("/workflows/{deedTypeCode}")
    public Map<String, Object> workflow(@PathVariable String deedTypeCode) {
        return config.workflow(CurrentUser.require().stateCode(), deedTypeCode);
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
