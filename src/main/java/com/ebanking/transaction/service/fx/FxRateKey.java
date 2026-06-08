package com.ebanking.transaction.service.fx;

import java.time.LocalDate;

/** Cache key for an FX rate lookup: (fromCurrency, toCurrency, date). */
public record FxRateKey(String fromCurrency, String toCurrency, LocalDate date) {}
