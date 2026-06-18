package com.ebanking.transaction.service.fx;

import com.ebanking.transaction.config.CacheConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches FX rates with two layers of protection:
 *
 * <ol>
 *   <li><b>Caffeine cache</b> — results are cached for 5 minutes (configured in
 *       {@link CacheConfig}) so the external provider is called at most once per
 *       currency pair per cache window.</li>
 *   <li><b>Resilience4j retry</b> — up to 3 attempts (500 ms apart) on a transient
 *       provider failure before the call is considered failed.</li>
 *   <li><b>Resilience4j circuit breaker</b> — opens after 50 % failures in a
 *       10-call sliding window. Once retries are exhausted (or the breaker is open)
 *       the fallback returns the last-known-good rate, or throws
 *       {@link FxRateUnavailableException} if no prior rate exists.</li>
 * </ol>
 *
 * <p>The {@code @Retry} aspect wraps {@code @CircuitBreaker} (Resilience4j's default
 * ordering), so the fallback fires only after all retry attempts have failed.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);

    private final FxRateClient client;

    /** In-memory last-known-good store; survives circuit-breaker open states. */
    private final Map<FxRateKey, BigDecimal> lastKnownRates = new ConcurrentHashMap<>();

    public FxRateService(FxRateClient client) {
        this.client = client;
    }

    /**
     * Returns the exchange rate for {@code fromCurrency → targetCurrency} on {@code date}.
     * Result is cached; on provider failure the last-known-good rate is returned.
     */
    @Cacheable(value = CacheConfig.FX_RATES_CACHE)
    @Retry(name = "fxProvider", fallbackMethod = "fallbackRate")
    @CircuitBreaker(name = "fxProvider")
    public BigDecimal getRate(String fromCurrency, String targetCurrency, LocalDate date) {
        BigDecimal rate = client.fetchRate(fromCurrency, targetCurrency, date);
        lastKnownRates.put(new FxRateKey(fromCurrency, targetCurrency, date), rate);
        log.debug("Fetched FX rate {}/{} on {}: {}", fromCurrency, targetCurrency, date, rate);
        return rate;
    }

    /** Resilience4j fallback — same signature + {@code Throwable}. Package-private for unit testing. */
    @SuppressWarnings("unused")
    BigDecimal fallbackRate(String fromCurrency, String targetCurrency,
                            LocalDate date, Throwable cause) {
        BigDecimal cached = lastKnownRates.get(new FxRateKey(fromCurrency, targetCurrency, date));
        if (cached != null) {
            log.warn("FX provider unavailable; using last-known rate for {}/{} on {}: {}",
                    fromCurrency, targetCurrency, date, cached);
            return cached;
        }
        log.error("FX provider unavailable and no cached rate for {}/{}", fromCurrency, targetCurrency);
        throw new FxRateUnavailableException(fromCurrency, targetCurrency);
    }
}
