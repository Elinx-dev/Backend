package in.gov.slate.config;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.CurrentUser;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService config;

    public ConfigController(ConfigService config) {
        this.config = config;
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
