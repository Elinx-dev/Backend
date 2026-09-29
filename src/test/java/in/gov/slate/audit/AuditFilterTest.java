package in.gov.slate.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import in.gov.slate.common.ApiException;

class AuditFilterTest {

    @Test
    void defaultsToTheNewestFiftyEntries() {
        AuditFilter filter = AuditFilter.builder().build();

        assertEquals("occurred_at", filter.sort());
        assertTrue(filter.descending());
        assertEquals(0, filter.page());
        assertEquals(50, filter.size());
        assertNull(filter.from());
    }

    @Test
    void normalisesReferencesAndDateOnlyBounds() {
        AuditFilter filter = AuditFilter.builder()
                .transactionRef(" txn-tn-2026-000012 ")
                .propertyRef("tn-adyar-00000004")
                .category("approval")
                .decision("rejected")
                .outcome("success")
                .from("2026-01-01")
                .to("2026-01-31")
                .direction("ASC")
                .build();

        assertEquals("TXN-TN-2026-000012", filter.transactionRef());
        assertEquals("TN-ADYAR-00000004", filter.propertyRef());
        assertEquals("APPROVAL", filter.category());
        assertEquals("REJECTED", filter.decision());
        assertEquals("SUCCESS", filter.outcome());
        assertEquals("2026-01-01T00:00Z", filter.from().toString());
        // The upper bound is exclusive, so a whole "to" day is included.
        assertEquals("2026-02-01T00:00Z", filter.to().toString());
        assertTrue(!filter.descending());
    }

    @Test
    void rejectsAnUnknownSortColumn() {
        ApiException error = assertThrows(ApiException.class,
                () -> AuditFilter.builder().sort("occurred_at; DROP TABLE sec.audit_log").build());

        assertTrue(error.getMessage().contains("sort must be one of"));
    }

    @Test
    void rejectsAnUnknownDirection() {
        assertThrows(ApiException.class, () -> AuditFilter.builder().direction("sideways").build());
    }

    @Test
    void rejectsAnInvalidDateRange() {
        assertThrows(ApiException.class,
                () -> AuditFilter.builder().from("2026-03-01").to("2026-02-01").build());
        assertThrows(ApiException.class,
                () -> AuditFilter.builder().from("2020-01-01").to("2026-01-01").build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().from("yesterday").build());
    }

    @Test
    void rejectsOutOfRangePagination() {
        assertThrows(ApiException.class, () -> AuditFilter.builder().page(-1).build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().size(0).build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().size(AuditFilter.MAX_PAGE_SIZE + 1).build());
    }

    @Test
    void rejectsUnsupportedFilterValues() {
        assertThrows(ApiException.class, () -> AuditFilter.builder().category("EVERYTHING").build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().category("SECURITY").build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().category("NAVIGATION").build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().decision("MAYBE").build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().outcome("PARTIAL").build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().actorUserId(0L).build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().action("LOGIN' OR '1'='1").build());
        assertThrows(ApiException.class, () -> AuditFilter.builder().search("x".repeat(121)).build());
    }

    @Test
    void offsetFollowsThePageSize() {
        assertEquals(60, AuditFilter.builder().page(3).size(20).build().offset());
    }
}
