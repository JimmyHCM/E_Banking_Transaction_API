package com.ebanking.transaction.api;

import com.ebanking.transaction.config.SecurityConfig;
import com.ebanking.transaction.dto.*;
import com.ebanking.transaction.service.TransactionQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Layer 4 — API contract tests.
 *
 * Validates that what the controller actually serializes conforms to the OpenAPI schema
 * declared in openapi.yaml, so the published contract cannot silently drift from the
 * implementation.
 *
 * Strategy: parse the controller's actual response body as a JSON tree and assert
 * structural and type constraints that mirror the TransactionPage schema.  This is
 * lighter than Spring Cloud Contract and sufficient to catch silent contract breaks.
 */
@WebMvcTest(TransactionController.class)
@Import(SecurityConfig.class)
class TransactionContractTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    TransactionQueryService queryService;

    private static final TransactionPageResponse SAMPLE_RESPONSE = new TransactionPageResponse(
            List.of(
                    new TransactionDto(
                            "89d3o179-abcd-465b-o9ee-e2d5f6ofeld46",
                            new MoneyDto("100.00", "GBP"),
                            "GB00BARC20201530093459",
                            "2020-10-01",
                            "Online payment")),
            new PageTotalsDto(
                    "CHF",
                    "2026-06-10",
                    "118.00",
                    "0.00",
                    Map.of("GBP/CHF", "1.18")),
            new PageMetadataDto(0, 50, 1L, 1));

    @BeforeAll
    static void setupMock() {
        // configured per-test via @MockBean setup in each test method
    }

    // ── Response structure conforms to TransactionPage schema ────────────

    @Test
    void responseBodyConformsToTransactionPageSchema() throws Exception {
        when(queryService.getTransactions(any(), anyInt(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(SAMPLE_RESPONSE);

        MvcResult result = mvc.perform(get("/api/v1/transactions?year=2020&month=10")
                        .with(jwt()
                                .jwt(j -> j.subject("P-0123456789"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_transactions:read"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        // Required top-level fields: content (array), totals (object), page (object)
        assertThat(root.has("content")).isTrue();
        assertThat(root.get("content").isArray()).isTrue();
        assertThat(root.has("totals")).isTrue();
        assertThat(root.get("totals").isObject()).isTrue();
        assertThat(root.has("page")).isTrue();
        assertThat(root.get("page").isObject()).isTrue();

        // Transaction item: required fields id, money, iban, valueDate, description
        JsonNode tx = root.get("content").get(0);
        assertThat(tx.has("id")).isTrue();
        assertThat(tx.has("money")).isTrue();
        assertThat(tx.get("money").has("amount")).isTrue();
        assertThat(tx.get("money").has("currency")).isTrue();
        assertThat(tx.has("iban")).isTrue();
        assertThat(tx.has("valueDate")).isTrue();
        assertThat(tx.has("description")).isTrue();

        // PageTotals: required fields currency, rateDate, totalCredit, totalDebit
        JsonNode totals = root.get("totals");
        assertThat(totals.has("currency")).isTrue();
        assertThat(totals.has("rateDate")).isTrue();
        assertThat(totals.has("totalCredit")).isTrue();
        assertThat(totals.has("totalDebit")).isTrue();

        // Monetary amounts are decimal strings, not JSON numbers
        assertThat(tx.get("money").get("amount").isTextual()).isTrue();
        assertThat(totals.get("totalCredit").isTextual()).isTrue();
        assertThat(totals.get("totalDebit").isTextual()).isTrue();

        // PageMetadata: required fields page, size, totalElements, totalPages
        JsonNode page = root.get("page");
        assertThat(page.has("page")).isTrue();
        assertThat(page.has("size")).isTrue();
        assertThat(page.has("totalElements")).isTrue();
        assertThat(page.has("totalPages")).isTrue();
    }

    // ── Currency pattern conforms to '^[A-Z]{3}$' ────────────────────────

    @Test
    void currencyFieldsMatchIso4217Pattern() throws Exception {
        when(queryService.getTransactions(any(), anyInt(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(SAMPLE_RESPONSE);

        MvcResult result = mvc.perform(get("/api/v1/transactions?year=2020&month=10")
                        .with(jwt()
                                .jwt(j -> j.subject("P-0123456789"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_transactions:read"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        String txCurrency  = root.get("content").get(0).get("money").get("currency").asText();
        String totCurrency = root.get("totals").get("currency").asText();

        assertThat(txCurrency).matches("[A-Z]{3}");
        assertThat(totCurrency).matches("[A-Z]{3}");
    }

    // ── Value date format is ISO 8601 date (yyyy-MM-dd) ───────────────────

    @Test
    void valueDateAndRateDateAreIso8601DateStrings() throws Exception {
        when(queryService.getTransactions(any(), anyInt(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(SAMPLE_RESPONSE);

        MvcResult result = mvc.perform(get("/api/v1/transactions?year=2020&month=10")
                        .with(jwt()
                                .jwt(j -> j.subject("P-0123456789"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_transactions:read"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        String valueDate = root.get("content").get(0).get("valueDate").asText();
        String rateDate  = root.get("totals").get("rateDate").asText();
        String isoDate   = "\\d{4}-\\d{2}-\\d{2}";

        assertThat(valueDate).matches(isoDate);
        assertThat(rateDate).matches(isoDate);
    }

    // ── X-Correlation-Id header is present ───────────────────────────────

    @Test
    void responseIncludesXCorrelationIdHeader() throws Exception {
        when(queryService.getTransactions(any(), anyInt(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(SAMPLE_RESPONSE);

        mvc.perform(get("/api/v1/transactions?year=2020&month=10")
                        .with(jwt()
                                .jwt(j -> j.subject("P-0123456789")
                                           .claim("jti", "correlation-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_transactions:read"))))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
