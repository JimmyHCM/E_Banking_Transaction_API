package com.ebanking.transaction.dto;

import java.util.Map;

/**
 * Per-page credit/debit totals, converted to {@code currency} at the FX rate
 * effective on {@code rateDate}.
 * All monetary values are decimal strings to preserve precision.
 */
public record PageTotalsDto(
        String currency,
        String rateDate,           // ISO 8601, e.g. "2026-06-08"
        String totalCredit,        // decimal string
        String totalDebit,         // decimal string, positive magnitude
        Map<String, String> fxRatesApplied   // e.g. { "GBP/EUR": "1.1723" }
) {}
