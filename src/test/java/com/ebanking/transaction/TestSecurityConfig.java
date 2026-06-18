package com.ebanking.transaction;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;

/**
 * Test security for the integration test.
 *
 * Rather than stand up a real authorization server, we supply a {@link JwtDecoder}
 * that turns any presented bearer token into a fixed JWT for the IT customer,
 * carrying the scope the API authorizes on. Defining this bean makes Spring Boot's
 * issuer-uri auto-configuration back off, so the PRODUCTION
 * {@link com.ebanking.transaction.config.SecurityConfig} filter chain still runs —
 * the IT therefore exercises the real authentication + scope-authorization path and
 * the controller receives a non-null {@code @AuthenticationPrincipal Jwt}.
 */
@TestConfiguration
public class TestSecurityConfig {

    /** Must match the customerId published in the integration test's Kafka event. */
    public static final String IT_CUSTOMER_ID = "P-IT-0000000001";

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(IT_CUSTOMER_ID)
                .claim("scope", "transactions:read")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
