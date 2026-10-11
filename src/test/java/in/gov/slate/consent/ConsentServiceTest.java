package in.gov.slate.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;
import in.gov.slate.connectors.AadhaarConnector;
import in.gov.slate.transaction.TransactionContext;
import in.gov.slate.transaction.TransactionRepository;
import in.gov.slate.transaction.TransactionService;
import in.gov.slate.transaction.WorkflowEngine;

@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private AadhaarConnector aadhaar;
    @Mock
    private TransactionRepository repository;
    @Mock
    private WorkflowEngine workflow;
    @Mock
    private AuditService audit;
    @Mock
    private TransactionService transactions;

    private ConsentService service;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        service = new ConsentService(jdbc, aadhaar, repository, workflow, audit, transactions);
        user = new CurrentUser(1L, "ro.adyar", "R. Anandhi", "TN", "REGISTRATION",
                Set.of("REGISTRATION_OFFICER"), Set.of("CONSENT_CAPTURE"), Set.of("ADYAR"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void finalPartyVerificationAdvancesToRuleChecks() {
        TransactionContext pending = context(List.of(
                Map.of("party_id", 10L, "status", "VERIFIED"),
                Map.of("party_id", 11L, "status", "PENDING")));
        TransactionContext verified = context(List.of(
                Map.of("party_id", 10L, "status", "VERIFIED"),
                Map.of("party_id", 11L, "status", "VERIFIED")));

        when(repository.load("TXN-TN-2026-000014", "TN")).thenReturn(pending, verified);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of(
                Map.of("id", 22L, "otp_request_reference", "AADHAAR-OTP-1",
                        "status", "PENDING", "attempt_count", 0)));
        when(aadhaar.verifyOtp("AADHAAR-OTP-1", "123456")).thenReturn(true);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        when(workflow.apply(eq(verified), eq("CONSENT_COMPLETE"), eq(null), eq(user)))
                .thenReturn("RULE_CHECK_PENDING");

        Map<String, Object> result = service.verify("TXN-TN-2026-000014", 11L, "123456");

        assertThat(result)
                .containsEntry("partyId", 11L)
                .containsEntry("status", "VERIFIED")
                .containsEntry("allPartiesVerified", true)
                .containsEntry("transactionStatus", "RULE_CHECK_PENDING");
        verify(workflow).apply(verified, "CONSENT_COMPLETE", null, user);
    }

    @Test
    void otpRequestStartsConsentWorkflowFromDraft() {
        TransactionContext draft = context("DRAFT", List.of());
        TransactionContext pending = context("CONSENT_PENDING", List.of());

        when(repository.load("TXN-TN-2026-000014", "TN")).thenReturn(draft, pending);
        when(workflow.apply(draft, "REQUEST_CONSENT", null, user)).thenReturn("CONSENT_PENDING");
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(ResultSetExtractor.class)))
                .thenReturn("CONSENT-V1");
        when(aadhaar.requestOtp("1234", "CONSENT-V1"))
                .thenReturn(new AadhaarConnector.OtpRequestResult("AADHAAR-OTP-1", 300, "XXXX-XXXX-1234", "123456"));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        List<Map<String, Object>> result = service.requestOtp("TXN-TN-2026-000014", List.of(10L));

        assertThat(result).singleElement().satisfies(otp -> assertThat(otp)
                .containsEntry("partyId", 10L)
                .containsEntry("demoOtp", "123456"));
        verify(workflow).apply(draft, "REQUEST_CONSENT", null, user);
        verify(workflow).requireStatus(pending, "CONSENT_PENDING", "Consent OTP request", user);
    }

    private TransactionContext context(List<Map<String, Object>> consents) {
        return context("CONSENT_PENDING", consents);
    }

    private TransactionContext context(String status, List<Map<String, Object>> consents) {
        return new TransactionContext(
                Map.of("id", 14L, "txn_ref", "TXN-TN-2026-000014", "status", status,
                        "workflow_id", 1L),
                Map.of("property_ref", "TN-CHN-00000014"),
                Map.of(),
                List.of(Map.of("id", 10L, "name", "Buyer", "aadhaar_captured", true, "aadhaar_last4", "1234"),
                        Map.of("id", 11L, "name", "Seller", "aadhaar_captured", true, "aadhaar_last4", "5678")),
                List.of(),
                consents,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
