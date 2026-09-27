package in.gov.slate.revenue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.connectors.RevenueConnector;
import in.gov.slate.connectors.model.RevenueModels.MutationPushResponse;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.WorkflowEngine;

@ExtendWith(MockitoExtension.class)
class RevenueServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private TransactionRepository repository;
    @Mock
    private WorkflowEngine workflow;
    @Mock
    private RevenueConnector connector;
    @Mock
    private AuditService audit;

    private RevenueService service;

    @BeforeEach
    void setUp() {
        service = new RevenueService(jdbc, repository, workflow, connector, audit, new ObjectMapper());
        CurrentUser user = new CurrentUser(7L, "tahsildar.demo", "Demo Tahsildar", "TN", "REVENUE",
                Set.of("TAHSILDAR"), Set.of("TXN_READ", "REVENUE_APPROVE"), Set.of(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void omitsUnsupportedLandTypeFromApprovedCurrentState() {
        Map<String, Object> mutation = Map.of(
                "id", 2L,
                "status", "TAHSILDAR_PENDING",
                "txn_ref", "TXN-TN-2026-000002",
                "property_id", 10L,
                "mutation_type", "FULL_PROPERTY_TRANSFER",
                "proposed_owner_set", "[{\"name\":\"Owner\"}]");
        TransactionContext context = new TransactionContext(
                Map.of("id", 13L, "txn_ref", "TXN-TN-2026-000002"),
                Map.of("property_ref", "TN-CHN-00000010", "land_type_code", "URBAN"),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of());

        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("SELECT m.*") ? List.of(mutation) : List.of();
        });
        when(repository.load("TXN-TN-2026-000002", "TN")).thenReturn(context);
        when(connector.pushMutation(any())).thenReturn(
                new MutationPushResponse("ACCEPTED", "MUT/4501/2026", "PATTA-9029"));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(ResultSetExtractor.class)))
                .thenReturn("DOC/2026/13");
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        when(workflow.apply(any(), anyString(), any(), any())).thenReturn("COMPLETED");

        service.approve(2L, new RevenueService.ApprovalRequest(null, null));

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(argThat(sql -> sql.contains("INSERT INTO revenue.current_state")), params.capture());
        assertThat(params.getValue().getValue("landContext")).isNull();
    }
}
