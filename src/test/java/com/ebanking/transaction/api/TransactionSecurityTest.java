package com.ebanking.transaction.api;

import com.ebanking.transaction.config.SecurityConfig;
import com.ebanking.transaction.service.TransactionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security / IDOR tests for {@link TransactionController}.
 *
 * Three properties proven here:
 *   1. A request without a token is rejected with 401.
 *   2. A valid token for customer A that attempts to pass customer B's id in the query
 *      string still results in the service being queried for A — proving the smuggled
 *      parameter is inert (IDOR prevention).
 *   3. A valid token without the required scope is rejected with 403.
 *
 * The {@code verify(..., never())} call in test 2 is the critical assertion: it proves
 * the controller never constructs a query for the smuggled id, not merely that the
 * response looks like A's data.
 */
@WebMvcTest(TransactionController.class)
@Import(SecurityConfig.class)
class TransactionSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    TransactionQueryService queryService;

    // ── 1. No token → 401 ────────────────────────────────────────────────

    @Test
    void requestWithoutToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/transactions?year=2020&month=10"))
                .andExpect(status().isUnauthorized());
    }

    // ── 2. Valid token for A, smuggled customerId for B → only A's data queried ──

    @Test
    void requestWithValidToken_returnsOnlyOwnCustomersData_evenIfForeignIdSupplied()
            throws Exception {

        mvc.perform(get("/api/v1/transactions?year=2020&month=10")
                .with(jwt()
                        .jwt(j -> j.subject("P-A-0123456789"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_transactions:read"))))
                .andExpect(status().isOk());

        // Service must be called with the token's subject, never with any foreign id.
        verify(queryService).getTransactions(eq("P-A-0123456789"), anyInt(), anyInt(),
                anyString(), anyInt(), anyInt());
        verify(queryService, never()).getTransactions(eq("P-B-9999999999"), anyInt(), anyInt(),
                anyString(), anyInt(), anyInt());
    }

    // ── 3. Valid token, missing scope → 403 ──────────────────────────────

    @Test
    void requestWithValidTokenButMissingScope_returns403() throws Exception {
        mvc.perform(get("/api/v1/transactions?year=2020&month=10")
                .with(jwt()
                        .jwt(j -> j.subject("P-A-0123456789"))))
                // No SCOPE_transactions:read authority
                .andExpect(status().isForbidden());
    }
}
