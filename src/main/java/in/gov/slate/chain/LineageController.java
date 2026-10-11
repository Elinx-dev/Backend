package in.gov.slate.chain;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final LineageService service;

    public LineageController(LineageService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> lineage(@RequestParam(required = false) String tokenRef,
                                       @RequestParam(required = false) String propertyRef,
                                       @RequestParam(required = false) Long propertyId) {
        return service.lineage(tokenRef, propertyRef, propertyId);
    }
}
