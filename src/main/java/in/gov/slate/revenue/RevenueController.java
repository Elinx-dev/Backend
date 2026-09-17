package in.gov.slate.revenue;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/revenue/mutations")
public class RevenueController {

    private final RevenueService revenue;

    public RevenueController(RevenueService revenue) {
        this.revenue = revenue;
    }

    @GetMapping
    public List<Map<String, Object>> queue(@RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "50") int limit) {
        return revenue.queue(status, limit);
    }

    @GetMapping("/{mutationId}")
    public Map<String, Object> detail(@PathVariable long mutationId) {
        return revenue.detail(mutationId);
    }

    @PostMapping("/{mutationId}/verify")
    public Map<String, Object> verify(@PathVariable long mutationId,
                                      @Valid @RequestBody RevenueService.VerifyRequest body) {
        return revenue.verifyAndForward(mutationId, body);
    }

    @PostMapping("/{mutationId}/objections")
    public Map<String, Object> objection(@PathVariable long mutationId,
                                         @Valid @RequestBody RevenueService.ObjectionRequest body) {
        return revenue.raiseObjection(mutationId, body);
    }

    @PostMapping("/{mutationId}/objections/dispose")
    public Map<String, Object> dispose(@PathVariable long mutationId,
                                       @Valid @RequestBody RevenueService.DisposalRequest body) {
        return revenue.disposeObjection(mutationId, body);
    }

    @PostMapping("/{mutationId}/approve")
    public Map<String, Object> approve(@PathVariable long mutationId,
                                       @Valid @RequestBody RevenueService.ApprovalRequest body) {
        return revenue.approve(mutationId, body);
    }
}
