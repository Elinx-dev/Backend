package in.gov.slate.property;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.IdempotencyService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyService properties;
    private final IdempotencyService idempotency;

    public PropertyController(PropertyService properties, IdempotencyService idempotency) {
        this.properties = properties;
        this.idempotency = idempotency;
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody PropertyService.CreatePropertyRequest body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var replay = idempotency.replay("property.create", key, body);
        if (replay.isPresent()) {
            return replay.get();
        }
        Map<String, Object> created = properties.create(body);
        idempotency.store("property.create", key, body, Map.of("propertyRef", created.get("property_ref")));
        return created;
    }

    @GetMapping({"/{propertyRef}", "/ref/{propertyRef}"})
    public Map<String, Object> get(@PathVariable String propertyRef) {
        return properties.get(propertyRef);
    }

    @GetMapping
    public List<Map<String, Object>> search(@RequestParam(required = false) String query,
                                            @RequestParam(required = false) String villageCode,
                                            @RequestParam(required = false) String surveyNo,
                                            @RequestParam(defaultValue = "50") int limit) {
        return properties.search(query, villageCode, surveyNo, Math.min(limit, 200));
    }
}
