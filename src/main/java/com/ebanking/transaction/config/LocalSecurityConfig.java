package com.ebanking.transaction.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Local-development-only JWT decoder.
 *
 * <p>Active only under the {@code local} Spring profile. It validates tokens signed with a
 * shared HMAC secret (HS256) instead of fetching JWKS from a real issuer, so you can mint
 * your own tokens (see the README) and exercise the API without standing up an OAuth2
 * authorization server.
 *
 * <p>This bean is {@code @ConditionalOnMissingBean}-friendly: when present, Spring Boot's
 * issuer-uri auto-configuration backs off, so {@link SecurityConfig} keeps applying its
 * authorization rules while delegating token validation here.
 *
 * <p><b>Never enable the {@code local} profile in production</b> — the secret is not
 * confidential and there is no issuer/audience validation.
 */
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${app.local.jwt.secret}") String secret) {
        SecretKeySpec key = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
