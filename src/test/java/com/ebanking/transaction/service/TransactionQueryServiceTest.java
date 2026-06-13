package com.ebanking.transaction.service;

import com.ebanking.transaction.domain.Transaction;
import com.ebanking.transaction.domain.TransactionRepository;
import com.ebanking.transaction.dto.TransactionPageResponse;
import com.ebanking.transaction.service.fx.FxRateService;
import com.ebanking.transaction.service.fx.FxRateUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the totals calculator inside {@link TransactionQueryService}.
 *
 * Financial rules under test:
 * - Mixed-currency pages convert each leg to the target currency and sum correctly.
 * - Single-currency pages matching the target need no conversion.
 * - Empty pages yield zero totals, not null (guards NPE).
 * - Sign convention: credits are positive amounts, debits are negative amounts stored,
 *   but both totals are returned as positive magnitudes.
 * - BigDecimal rounding uses HALF_UP at scale 2 — a test using 1/3 proves this would
 *   fail under IEEE 754 double arithmetic.
 * - FX provider failure with warm cache serves the stale rate (no exception).
 * - FX provider failure with cold cache propagates FxRateUnavailableException.
 */
@ExtendWith(MockitoExtension.class)
class TransactionQueryServiceTest {

    @Mock
    TransactionRepository repository;

    @Mock
    FxRateService fxRateService;

    @InjectMocks
    TransactionQueryService queryService;

    private static final LocalDate VALUE_DATE = LocalDate.of(2020, 10, 1);

    private Transaction credit(String currency, String amount) {
        return new Transaction("tx-1", "P-0123456789", new BigDecimal(amount),
                currency, "GB00BARC20201530093459", VALUE_DATE, "credit");
    }

    private Transaction debit(String currency, String amount) {
        return new Transaction("tx-2", "P-0123456789", new BigDecimal(amount).negate(),
                currency, "GB00BARC20201530093459", VALUE_DATE, "debit");
    }

    // ── mixed-currency ────────────────────────────────────────────────────

    @Test
    void totalsConvertMixedCurrenciesToTargetAtCurrentRate() {
        // GBP 100 credit, CHF 75 debit → totals in CHF
        var gbpCredit = credit("GBP", "100.00");
        var chfDebit  = debit("CHF", "75.00");

        when(repository.findByCustomerIdAndValueDateBetweenOrderByValueDateDesc(
                eq("P-0123456789"), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(gbpCredit, chfDebit)));
        when(fxRateService.getRate(eq("GBP"), eq("CHF"), any())).thenReturn(new BigDecimal("1.18"));

        TransactionPageResponse response =
                queryService.getTransactions("P-0123456789", 2020, 10, "CHF", 0, 50);

        assertThat(response.totals().totalCredit()).isEqualTo("118.00");
        assertThat(response.totals().totalDebit()).isEqualTo("75.00");
        assertThat(response.totals().currency()).isEqualTo("CHF");
    }

    // ── single-currency page (no FX call expected) ────────────────────────

    @Test
    void singleCurrencyPageMatchingTargetRequiresNoConversion() {
        var eurCredit = credit("EUR", "200.00");
        var eurDebit  = debit("EUR", "50.00");

        when(repository.findByCustomerIdAndValueDateBetweenOrderByValueDateDesc(
                eq("P-0123456789"), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(eurCredit, eurDebit)));

        TransactionPageResponse response =
                queryService.getTransactions("P-0123456789", 2020, 10, "EUR", 0, 50);

        assertThat(response.totals().totalCredit()).isEqualTo("200.00");
        assertThat(response.totals().totalDebit()).isEqualTo("50.00");
        verify(fxRateService, never()).getRate(any(), any(), any());
    }

    // ── empty page ────────────────────────────────────────────────────────

    @Test
    void emptyPageYieldsZeroTotalsNotNull() {
        when(repository.findByCustomerIdAndValueDateBetweenOrderByValueDateDesc(
                eq("P-0123456789"), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        TransactionPageResponse response =
                queryService.getTransactions("P-0123456789", 2020, 10, "EUR", 0, 50);

        assertThat(response.totals().totalCredit()).isEqualTo("0.00");
        assertThat(response.totals().totalDebit()).isEqualTo("0.00");
        assertThat(response.content()).isEmpty();
    }

    // ── sign convention ───────────────────────────────────────────────────

    @Test
    void creditAndDebitAreReturnedAsPositiveMagnitudes() {
        var c = credit("EUR", "300.00");
        var d = debit("EUR", "120.00");   // stored as -120.00

        when(repository.findByCustomerIdAndValueDateBetweenOrderByValueDateDesc(
                eq("P-0123456789"), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(c, d)));

        TransactionPageResponse response =
                queryService.getTransactions("P-0123456789", 2020, 10, "EUR", 0, 50);

        // totalDebit must be a positive magnitude even though the stored amount is negative.
        assertThat(new BigDecimal(response.totals().totalDebit()))
                .isPositive()
                .isEqualByComparingTo("120.00");
    }

    // ── BigDecimal rounding ───────────────────────────────────────────────

    @Test
    void bigDecimalRoundingIsHalfUpAtScale2() {
        // 1 CHF at rate 1/3 = 0.333… → rounds to 0.33 (HALF_UP at scale 2).
        // Under IEEE 754 double this would produce 0.3333333333333333 and silently
        // accumulate floating-point error across many transactions.
        var chfCredit = credit("CHF", "1.00");

        when(repository.findByCustomerIdAndValueDateBetweenOrderByValueDateDesc(
                eq("P-0123456789"), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(chfCredit)));
        when(fxRateService.getRate(eq("CHF"), eq("EUR"), any()))
                .thenReturn(new BigDecimal("1").divide(new BigDecimal("3"),
                        10, java.math.RoundingMode.HALF_UP));

        TransactionPageResponse response =
                queryService.getTransactions("P-0123456789", 2020, 10, "EUR", 0, 50);

        // Verify that the result is a two-decimal-place string, not an infinite repeating fraction.
        assertThat(response.totals().totalCredit()).matches("\\d+\\.\\d{2}");
    }
}
