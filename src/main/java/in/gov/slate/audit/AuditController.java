package in.gov.slate.audit;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The audit trail API. Filters are validated in {@link AuditFilter}; the
 * caller's state and role decide which rows the filter is allowed to reach.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditQueryService audit;

    public AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    public record UiEventRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{1,60}") String action,
                                 @NotBlank @Size(max = 200) String page,
                                 @Size(max = 60) String entityType,
                                 @Size(max = 64) String entityId,
                                 @Size(max = 64) String transactionRef,
                                 @Size(max = 64) String propertyRef,
                                 @Size(max = 500) String detail) {
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(AuditQueryParams params) {
        return audit.search(params.toFilter());
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(AuditQueryParams params) {
        return audit.summary(params.toFilter());
    }

    @GetMapping("/filters")
    public Map<String, Object> filters() {
        return audit.facets();
    }

    @GetMapping("/logs/{id}")
    public Map<String, Object> entry(@PathVariable long id) {
        return audit.entry(id);
    }

    @GetMapping("/transactions/{transactionRef}")
    public List<Map<String, Object>> transactionTimeline(@PathVariable String transactionRef,
                                                         @RequestParam(required = false) Integer size) {
        return audit.timeline(transactionRef, null, size);
    }

    @GetMapping("/properties/{propertyRef}")
    public List<Map<String, Object>> propertyTimeline(@PathVariable String propertyRef,
                                                      @RequestParam(required = false) Integer size) {
        return audit.timeline(null, propertyRef, size);
    }

    @GetMapping("/export")
    public ResponseEntity<String> export(AuditQueryParams params) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-trail.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(audit.csv(params.toFilter()));
    }

    @PostMapping("/events")
    public Map<String, Object> event(@Valid @RequestBody UiEventRequest request) {
        audit.recordUiEvent(request.action(), request.page(), request.entityType(), request.entityId(),
                request.transactionRef(), request.propertyRef(), request.detail());
        return Map.of("recorded", true);
    }
}
