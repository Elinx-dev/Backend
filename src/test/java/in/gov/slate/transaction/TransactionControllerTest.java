package in.gov.slate.transaction;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import in.gov.slate.common.IdempotencyService;

@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc(addFilters = false)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactions;

    @MockBean
    private IdempotencyService idempotency;

    @Test
    void detailAcceptsRefPrefixedRoute() throws Exception {
        String txnRef = "TXN-TN-2026-000007";
        when(transactions.detail(txnRef)).thenReturn(Map.of("txn_ref", txnRef));

        mockMvc.perform(get("/api/transactions/ref/{txnRef}", txnRef))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txn_ref").value(txnRef));

        verify(transactions).detail(txnRef);
    }
}
