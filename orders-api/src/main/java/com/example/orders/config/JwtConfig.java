package com.example.orders.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Configuration
public class JwtConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecretKey jwtSecretKey(@Value("${security.jwt.secret}") String encodedSecret) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_SECRET must be valid Base64");
        }
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 decoded bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey,
                                 @Value("${security.jwt.issuer}") String issuer, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<Instant>("iat",
                        issuedAt -> issuedAt != null && !issuedAt.isAfter(clock.instant())),
                new JwtClaimValidator<Instant>("exp",
                        expiration -> expiration != null && clock.instant().isBefore(expiration)),
                new JwtClaimValidator<String>("sub",
                        subject -> subject != null && !subject.isBlank())));
        return decoder;
    }
}
