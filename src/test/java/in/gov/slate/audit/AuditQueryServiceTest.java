package in.gov.slate.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.AuditEvent;
import in.gov.slate.common.AuditService;
import in.gov.slate.common.CurrentUser;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock
    private AuditRepository repository;

    @Mock
    private AuditService audit;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAdministratorSeesEveryActionInTheState() {
        authenticate(administrator());
        when(repository.search(any(), eq("TN"))).thenReturn(List.of(Map.of("id", 1L)));
        when(repository.count(any(), eq("TN"))).thenReturn(120L);

        Map<String, Object> response = service().search(AuditFilter.builder().size(50));

        assertEquals("STATE", response.get("scope"));
        assertEquals(120L, response.get("total"));
        assertEquals(3, response.get("totalPages"));
        AuditFilter filter = captureFilter();
        assertEquals(null, filter.actorUsername());
        assertTrue(filter.stateWide());
    }

    @Test
    void anOfficerSeesActionsRelatedToTheirAssignedTransactions() {
        authenticate(officer());
        when(repository.search(any(), eq("TN"))).thenReturn(List.of());
        when(repository.count(any(), eq("TN"))).thenReturn(0L);

        Map<String, Object> response = service().search(AuditFilter.builder());

        assertEquals("RELATED", response.get("scope"));
        AuditFilter filter = captureFilter();
        assertEquals(1L, filter.visibleUserId());
        assertEquals(List.of("ADYAR"), filter.visibleSroCodes());
        assertEquals(false, filter.stateWide());
    }

    @Test
    void anOfficerCannotOpenAnUnrelatedEntry() {
        authenticate(officer());
        when(repository.findVisibleById(eq(9L), any(), eq("TN"))).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service().entry(9L));
    }

    @Test
    void aMissingEntryIsNotFound() {
        authenticate(officer());
        when(repository.findVisibleById(eq(9L), any(), eq("TN"))).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service().entry(9L));
    }

    @Test
    void aTimelineNeedsATransactionOrProperty() {
        authenticate(officer());

        assertThrows(ApiException.class, () -> service().timeline(null, null, null));
    }

    @Test
    void exportingIsItselfAudited() {
        authenticate(administrator());
        when(repository.search(any(), eq("TN"))).thenReturn(List.of(Map.of(
                "id", 1L, "action", "MUTATION_APPROVED", "decision", "APPROVED",
                "detail", "Remarks with a \" quote and, comma")));

        String csv = service().csv(AuditFilter.builder());

        assertTrue(csv.startsWith("id,occurred_at,actor_username"));
        assertTrue(csv.contains("\"Remarks with a \"\" quote and, comma\""));
        ArgumentCaptor<AuditEvent.Builder> event = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(audit).record(event.capture());
        assertEquals("AUDIT_TRAIL_EXPORTED", event.getValue().build().action());
    }

    @Test
    void aUiEventIsRecordedAsNavigation() {
        authenticate(officer());

        service().recordUiEvent("AUDIT_TRAIL_VIEWED", "/audit", null, null, "TXN-TN-2026-000012", null, null);

        ArgumentCaptor<AuditEvent.Builder> event = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(audit).record(event.capture());
        AuditEvent recorded = event.getValue().build();
        assertEquals(AuditEvent.CATEGORY_NAVIGATION, recorded.category());
        assertEquals("SCREEN", recorded.entityType());
        assertEquals("/audit", recorded.entityId());
        assertEquals("TXN-TN-2026-000012", recorded.transactionRef());
    }

    private AuditQueryService service() {
        return new AuditQueryService(repository, audit);
    }

    private AuditFilter captureFilter() {
        ArgumentCaptor<AuditFilter> filter = ArgumentCaptor.forClass(AuditFilter.class);
        verify(repository).search(filter.capture(), eq("TN"));
        return filter.getValue();
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }

    private CurrentUser administrator() {
        return new CurrentUser(2L, "admin.tn", "State Administrator", "TN", "REGISTRATION",
                Set.of("STATE_ADMIN"), Set.of(), Set.of(), Set.of());
    }

    private CurrentUser officer() {
        return new CurrentUser(1L, "ro.adyar", "R. Anandhi", "TN", "REGISTRATION",
                Set.of("REGISTRATION_OFFICER"), Set.of(), Set.of("ADYAR"), Set.of());
    }
}
