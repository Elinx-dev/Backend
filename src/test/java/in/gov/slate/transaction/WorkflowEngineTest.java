package in.gov.slate.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.config.ConfigService;

@ExtendWith(MockitoExtension.class)
class WorkflowEngineTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private ConfigService config;

    @Mock
    private ValidationEngine validation;

    @Mock
    private AuditService audit;

    @Test
    void unavailableActionExplainsRequiredStatusAndNextAction() throws Exception {
        Array roles = mock(Array.class);
        when(roles.getArray()).thenReturn(new String[]{"REGISTRATION_OFFICER"});

        Map<String, Object> requestConsent = new HashMap<>();
        requestConsent.put("action_code", "REQUEST_CONSENT");
        requestConsent.put("to_status", "CONSENT_PENDING");
        requestConsent.put("allowed_roles", roles);
        requestConsent.put("guard_expr", "partiesAndWitnessesComplete");
        requestConsent.put("requires_reason", false);

        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(List.of(requestConsent));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), any(Class.class)))
                .thenReturn(List.of("SUBMITTED"));
        when(validation.guard("partiesAndWitnessesComplete", context())).thenReturn(true);

        WorkflowEngine workflow = new WorkflowEngine(jdbc, config, validation, audit);

        ApiException error = assertThrows(ApiException.class,
                () -> workflow.requireAvailable(context(), "REGISTER", user()));

        assertEquals("CONFLICT", error.getCode());
        assertEquals("Action REGISTER cannot be performed from status DRAFT. Required status: SUBMITTED. "
                + "Available actions from DRAFT: REQUEST_CONSENT", error.getMessage());
    }

    @Test
    void requireStatusExplainsHowToAdvance() throws Exception {
        Array roles = mock(Array.class);
        when(roles.getArray()).thenReturn(new String[]{"REGISTRATION_OFFICER"});

        Map<String, Object> requestConsent = new HashMap<>();
        requestConsent.put("action_code", "REQUEST_CONSENT");
        requestConsent.put("to_status", "CONSENT_PENDING");
        requestConsent.put("allowed_roles", roles);
        requestConsent.put("guard_expr", null);
        requestConsent.put("requires_reason", false);

        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(List.of(requestConsent));

        WorkflowEngine workflow = new WorkflowEngine(jdbc, config, validation, audit);

        ApiException error = assertThrows(ApiException.class,
                () -> workflow.requireStatus(context(), "RULE_CHECK_PENDING", "Rule checks", user()));

        assertEquals("Rule checks cannot be performed from status DRAFT. Required status: RULE_CHECK_PENDING. "
                + "Available actions from DRAFT: REQUEST_CONSENT", error.getMessage());
    }

    private TransactionContext context() {
        return new TransactionContext(
                Map.of("id", 12L, "txn_ref", "TXN-TN-2026-000010", "status", "DRAFT", "workflow_id", 1L),
                Map.of("property_ref", "TN-CHN-00000001"),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of());
    }

    private CurrentUser user() {
        return new CurrentUser(1L, "ro.adyar", "R. Anandhi", "TN", "REGISTRATION",
                Set.of("REGISTRATION_OFFICER"), Set.of("REGISTER"), Set.of(), Set.of());
    }
}
