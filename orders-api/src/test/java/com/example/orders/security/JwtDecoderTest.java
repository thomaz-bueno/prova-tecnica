package com.example.orders.security;

import com.example.orders.config.JwtConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtDecoderTest {

    @ParameterizedTest
    @ValueSource(strings = {"not-base64!", "c2hvcnQ="})
    void rejectsMalformedOrWeakSigningSecrets(String secret) {
        assertThatThrownBy(() -> new JwtConfig().jwtSecretKey(secret))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsGatewayFormatTokensExactlyAtExpiration() {
        Instant issuedAt = Instant.parse("2026-10-07T12:00:00Z");
        String token = JwtTestTokens.sign(JwtClaimsSet.builder().issuer(JwtTestTokens.ISSUER)
                .subject("usuario").issuedAt(issuedAt).notBefore(issuedAt)
                .expiresAt(issuedAt.plusSeconds(3600)).claim("roles", List.of("USER")).build());
        var config = new JwtConfig();
        var key = config.jwtSecretKey(JwtTestTokens.SECRET);
        var before = config.jwtDecoder(key, JwtTestTokens.ISSUER,
                Clock.fixed(issuedAt.plusSeconds(3599), ZoneOffset.UTC));
        var expired = config.jwtDecoder(key, JwtTestTokens.ISSUER,
                Clock.fixed(issuedAt.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(before.decode(token).getSubject()).isEqualTo("usuario");
        assertThatThrownBy(() -> expired.decode(token)).isInstanceOf(JwtValidationException.class);
    }
}
