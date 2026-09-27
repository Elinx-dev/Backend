package in.gov.slate.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.WorkflowEngine;

@ExtendWith(MockitoExtension.class)
class FeeServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private TransactionRepository repository;
    @Mock
    private WorkflowEngine workflow;
    @Mock
    private AuditService audit;

    private FeeService service;

    @BeforeEach
    void setUp() {
        service = new FeeService(jdbc, repository, workflow, audit, new ObjectMapper());
        CurrentUser user = new CurrentUser(1L, "ro.adyar", "R. Anandhi", "TN", "REGISTRATION",
                Set.of("REGISTRATION_OFFICER"), Set.of("FEE_CALCULATE"), Set.of("ADYAR"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesPropertyGuidelineWhenVillageMasterIsMissing() {
        TransactionContext context = new TransactionContext(
                Map.of("id", 13L, "txn_ref", "TXN-TN-2026-000011", "status", "FEE_PAYMENT_PENDING",
                        "transfer_scope", "FULL_PROPERTY"),
                Map.of("property_ref", "TN-CHENNAI_SOUTH-00000014", "state_code", "TN",
                        "village_code", "sk004", "land_type_code", "RURAL", "extent_value", new BigDecimal("1000"),
                        "extent_unit", "SQ_FT", "guideline_value", new BigDecimal("100"),
                        "guideline_value_reference", "tets", "street", "Demo street"),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of());
        when(repository.load("TXN-TN-2026-000011", "TN")).thenReturn(context);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());

        Map<String, Object> result = service.guidelineValue("TXN-TN-2026-000011");

        assertThat(result)
                .containsEntry("ratePerUnit", new BigDecimal("100"))
                .containsEntry("extentConsidered", new BigDecimal("1000"))
                .containsEntry("guidelineValue", new BigDecimal("100000.00"))
                .containsEntry("notificationReference", "tets");
    }
}
