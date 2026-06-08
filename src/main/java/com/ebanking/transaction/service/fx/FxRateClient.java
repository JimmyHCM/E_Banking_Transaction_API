package com.ebanking.transaction.service.fx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Thin HTTP client for the external FX rate provider.
 * Called only on a Caffeine cache miss; circuit-broken in {@link FxRateService}.
 *
 * The URL template and response shape below target the open.er-api.com format.
 * Adapt to your actual provider without changing the public contract.
 */
@Component
public class FxRateClient {

    private final RestClient restClient;

    public FxRateClient(
            RestClient.Builder builder,
            @Value("${app.fx.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Fetches the exchange rate from {@code fromCurrency} to {@code toCurrency}
     * for the given {@code date}.
     *
     * Expected response body (provider-specific):
     * <pre>{ "rates": { "EUR": 1.1723 } }</pre>
     */
    @SuppressWarnings("unchecked")
    public BigDecimal fetchRate(String fromCurrency, String toCurrency, LocalDate date) {
        Map<String, Object> response = restClient.get()
                .uri("/{date}?base={from}&symbols={to}", date, fromCurrency, toCurrency)
                .retrieve()
                .body(Map.class);

        Map<String, Number> rates = (Map<String, Number>) response.get("rates");
        Number rate = rates.get(toCurrency);
        if (rate == null) {
            throw new IllegalStateException(
                    "FX provider returned no rate for " + toCurrency);
        }
        return new BigDecimal(rate.toString());
    }
}
