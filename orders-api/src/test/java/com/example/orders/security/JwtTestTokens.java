package com.example.orders.security;

import com.example.orders.config.JwtConfig;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

public final class JwtTestTokens {

    public static final String SECRET = randomSecret();
    public static final String ISSUER = "gateway-api";

    private JwtTestTokens() {
    }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static String validToken(String... roles) {
        Instant now = Instant.now();
        return sign(JwtClaimsSet.builder().issuer(ISSUER).subject("usuario")
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(3600))
                .claim("roles", List.of(roles)).build());
    }

    public static RequestPostProcessor bearer() {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"));
            return request;
        };
    }

    public static String sign(JwtClaimsSet claims) {
        return sign(claims, SECRET);
    }

    public static String sign(JwtClaimsSet claims, String secret) {
        var key = new JwtConfig().jwtSecretKey(secret);
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }
}
