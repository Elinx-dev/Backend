package in.gov.slate.dashboard;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('STATE_ADMIN')")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /**
     * State-level activity overview. {@code state} is a state code or {@code ALL};
     * {@code period} is TODAY, WEEK, MONTH or CUSTOM (with {@code from}/{@code to}).
     */
    @GetMapping
    public Map<String, Object> overview(@RequestParam(required = false) String state,
                                        @RequestParam(required = false) String period,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        return dashboard.overview(state, period, from, to);
    }

    /**
     * Searchable rows behind the dashboard. {@code dataset} is TRANSACTIONS, PROPERTIES,
     * RULE_CHECKS or ISSUES; {@code q} matches references, offices and details;
     * {@code status} narrows by transaction status, property status, rule outcome or issue type.
     */
    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(required = false) String state,
                                       @RequestParam(required = false) String period,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String dataset,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String status) {
        return dashboard.records(state, period, from, to, dataset, q, status);
    }
}
