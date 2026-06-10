package com.ebanking.transaction.service.fx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FxRateService} focusing on the circuit-breaker fallback logic.
 *
 * NOTE: These tests invoke {@code fallbackRate()} directly, which exercises the fallback
 * *logic* without triggering the Resilience4j AOP proxy.  The integration test
 * (WireMock returning 503s) is the place that proves the annotation routes to the
 * fallback under a real proxy.  Separating the two avoids conflating AOP wiring
 * with domain behaviour.
 */
@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    @Mock
    FxRateClient fxClient;

    @InjectMocks
    FxRateService fxRateService;

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    // ── warm-cache fallback ────────────────────────────────────────────────

    @Test
    void returnsLastKnownRateWhenProviderFails() {
        // Prime the last-known-good store by invoking the happy path first.
        when(fxClient.fetchRate("GBP", "CHF", TODAY)).thenReturn(new BigDecimal("1.18"));
        fxRateService.getRate("GBP", "CHF", TODAY);

        // Provider now fails; fallback should serve the cached value.
        RuntimeException cause = new RuntimeException("503 Service Unavailable");
        BigDecimal result = fxRateService.fallbackRate("GBP", "CHF", TODAY, cause);

        assertThat(result).isEqualByComparingTo("1.18");
    }

    @Test
    void propagatesWhenProviderFailsAndNoCachedRateExists() {
        // Cold cache + provider failure → FxRateUnavailableException surfaced as 502 upstream.
        RuntimeException cause = new RuntimeException("503 Service Unavailable");

        assertThatThrownBy(() -> fxRateService.fallbackRate("GBP", "CHF", TODAY, cause))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("GBP/CHF");
    }

    // ── happy-path sanity ──────────────────────────────────────────────────

    @Test
    void returnsRateFromClientOnSuccessfulCall() {
        when(fxClient.fetchRate("USD", "EUR", TODAY)).thenReturn(new BigDecimal("0.92"));

        BigDecimal rate = fxRateService.getRate("USD", "EUR", TODAY);

        assertThat(rate).isEqualByComparingTo("0.92");
    }

    // ── BigDecimal safety ─────────────────────────────────────────────────

    @Test
    void storedRatePreservesBigDecimalPrecision() {
        // This test would fail if the implementation used double at any point,
        // because 1.005 cannot be represented exactly in IEEE 754.
        BigDecimal precise = new BigDecimal("1.005");
        when(fxClient.fetchRate("CHF", "EUR", TODAY)).thenReturn(precise);

        BigDecimal rate = fxRateService.getRate("CHF", "EUR", TODAY);

        assertThat(rate).isEqualByComparingTo(precise);
        // Explicitly ensure it is NOT rounded to the nearest double representation.
        assertThat(rate.toPlainString()).isEqualTo("1.005");
    }
}
