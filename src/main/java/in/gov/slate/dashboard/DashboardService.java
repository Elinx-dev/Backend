package in.gov.slate.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.gov.slate.common.ApiException;

/** Assembles the state-level activity overview shown on the admin dashboard. */
@Service
public class DashboardService {

    private final DashboardRepository repository;
    private final Clock clock;

    @Autowired
    public DashboardService(DashboardRepository repository) {
        this(repository, Clock.system(DashboardFilter.ZONE));
    }

    DashboardService(DashboardRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(String state, String period, String from, String to) {
        List<Map<String, Object>> states = repository.states();
        DashboardFilter filter = resolve(states, state, period, from, to);

        Map<String, Object> failures = new LinkedHashMap<>(repository.failureOverview(filter));
        failures.put("ruleReasons", repository.ruleFailureReasons(filter));
        failures.put("failedActionsByType", repository.failedActions(filter));

        Map<String, Object> revenue = new LinkedHashMap<>(repository.vaoActivity(filter));
        revenue.put("byStatus", repository.mutationsByStatus(filter));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filter", filterView(filter));
        response.put("states", states);
        response.put("summary", repository.summary(filter));
        response.put("transactionsByStatus", repository.transactionsByStatus(filter));
        response.put("transactionsByDeedType", repository.transactionsByDeedType(filter));
        response.put("trend", repository.trend(filter));
        response.put("ruleChecks", repository.ruleOutcomes(filter));
        response.put("failures", failures);
        response.put("revenue", revenue);
        response.put("byOffice", repository.byOffice(filter));
        response.put("byState", repository.byState(filter));
        response.put("recentIssues", repository.recentIssues(filter));
        response.put("generatedAt", OffsetDateTime.now(clock).toString());
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> records(String state, String period, String from, String to,
                                       String dataset, String search, String status) {
        DashboardFilter filter = resolve(repository.states(), state, period, from, to);
        DashboardRecordQuery query = DashboardRecordQuery.of(dataset, search, status);
        Map<String, Object> result = repository.records(filter, query);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filter", filterView(filter));
        response.put("dataset", query.dataset().name());
        response.put("search", query.search());
        response.put("status", query.status());
        response.put("limit", DashboardRecordQuery.LIMIT);
        response.put("total", result.get("total"));
        response.put("rows", result.get("rows"));
        return response;
    }

    private DashboardFilter resolve(List<Map<String, Object>> states, String state, String period,
                                    String from, String to) {
        DashboardFilter filter = DashboardFilter.of(state, period, from, to, LocalDate.now(clock));
        if (!filter.allStates() && states.stream().noneMatch(s -> filter.stateCode().equals(s.get("code")))) {
            throw ApiException.badRequest("Unknown state code " + filter.stateCode());
        }
        return filter;
    }

    private static Map<String, Object> filterView(DashboardFilter filter) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stateCode", filter.allStates() ? DashboardFilter.ALL_STATES : filter.stateCode());
        view.put("period", filter.period().name());
        view.put("from", filter.fromDate().toString());
        view.put("to", filter.toDate().toString());
        view.put("bucket", filter.bucket().name());
        view.put("timezone", DashboardFilter.ZONE.getId());
        return view;
    }
}
