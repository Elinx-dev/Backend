package in.gov.slate.dashboard;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.CurrentUser;
import in.gov.slate.security.AdminStateScope;

@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasAnyRole('STATE_ADMIN', 'CENTRAL_ADMIN')")
public class DashboardController {

    private final DashboardService dashboard;
    private final AdminStateScope stateScope;

    public DashboardController(DashboardService dashboard, AdminStateScope stateScope) {
        this.dashboard = dashboard;
        this.stateScope = stateScope;
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
        String targetState = stateScope.resolveDashboardState(state);
        Map<String, Object> result = dashboard.overview(targetState, period, from, to);
        if (!CurrentUser.require().hasRole("CENTRAL_ADMIN")) {
            result.put("states", stateScope.states());
        }
        return result;
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
        String targetState = stateScope.resolveDashboardState(state);
        return dashboard.records(targetState, period, from, to, dataset, q, status);
    }
}
