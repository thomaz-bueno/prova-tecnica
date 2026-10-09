package com.example.gateway.security;

import com.example.gateway.TestJwtSecrets;
import com.example.gateway.config.JwtConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtConfig config = new JwtConfig();
    private final SecretKey key = config.jwtSecretKey(TestJwtSecrets.randomSecret());
    private final JwtEncoder encoder = config.jwtEncoder(key);
    private final Instant issuedAt = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void signsAndValidatesAnExactlyOneHourToken() {
        String token = tokenAt(issuedAt);
        Jwt jwt = decoderAt(issuedAt).decode(token);
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256").containsEntry("typ", "JWT");
        assertThat(jwt.getIssuedAt()).isEqualTo(issuedAt);
        assertThat(jwt.getExpiresAt()).isEqualTo(issuedAt.plusSeconds(3600));
        assertThat(jwt.getSubject()).isEqualTo("usuario");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getClaims()).doesNotContainKeys("password", "password_hash");
    }

    @Test
    void rejectsTheTokenAtTheExpirationBoundaryWithoutClockSkew() {
        String token = tokenAt(issuedAt);
        assertThat(decoderAt(issuedAt.plusSeconds(3599)).decode(token).getSubject()).isEqualTo("usuario");
        assertThatThrownBy(() -> decoderAt(issuedAt.plusSeconds(3600)).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-valid-base64!", "c2hvcnQ="})
    void rejectsMalformedAndWeakSecretConfiguration(String encodedSecret) {
        assertThatThrownBy(() -> config.jwtSecretKey(encodedSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(encodedSecret);
    }

    private String tokenAt(Instant instant) {
        JwtService service = new JwtService(encoder, Clock.fixed(instant, ZoneOffset.UTC), "gateway-api");
        return service.generateToken(UsernamePasswordAuthenticationToken.authenticated(
                "usuario", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private JwtDecoder decoderAt(Instant instant) {
        return config.jwtDecoder(key, "gateway-api", Clock.fixed(instant, ZoneOffset.UTC));
    }
}
