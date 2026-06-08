package com.ebanking.transaction.service.fx;

/** Thrown when the FX provider is unavailable and no last-known-good rate exists. */
public class FxRateUnavailableException extends RuntimeException {

    public FxRateUnavailableException(String fromCurrency, String toCurrency) {
        super("No exchange rate available for " + fromCurrency + "/" + toCurrency
                + " — provider unreachable and no cached fallback.");
    }
}
