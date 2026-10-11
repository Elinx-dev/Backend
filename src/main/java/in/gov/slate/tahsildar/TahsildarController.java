package in.gov.slate.tahsildar;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.revenue.RevenueService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/tahsildar")
@PreAuthorize("hasRole('TAHSILDAR')")
public class TahsildarController {

    private final TahsildarService service;

    public TahsildarController(TahsildarService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return service.dashboard();
    }

    @GetMapping("/records")
    public List<Map<String, Object>> records() {
        return service.records();
    }

    @GetMapping("/records/{txnRef}")
    public Map<String, Object> record(@PathVariable String txnRef) {
        return service.record(txnRef);
    }

    @PostMapping("/records/{txnRef}/approve")
    public Map<String, Object> approve(@PathVariable String txnRef,
                                       @Valid @RequestBody RevenueService.ApprovalRequest body) {
        return service.approve(txnRef, body);
    }
}
