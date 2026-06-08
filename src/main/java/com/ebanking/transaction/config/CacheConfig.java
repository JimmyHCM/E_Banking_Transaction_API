package com.ebanking.transaction.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /** Cache name used by {@code FxRateService}. */
    public static final String FX_RATES_CACHE = "fxRates";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(FX_RATES_CACHE);
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500)
                .recordStats()           // exposes hit/miss ratio via Micrometer
        );
        return manager;
    }
}
