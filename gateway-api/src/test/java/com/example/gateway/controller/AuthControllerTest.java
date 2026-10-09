package com.example.gateway.controller;

import com.example.gateway.TestJwtSecrets;
import com.example.gateway.config.JwtConfig;
import com.example.gateway.config.SecurityConfig;
import com.example.gateway.entity.UserAccount;
import com.example.gateway.repository.UserAccountRepository;
import com.example.gateway.security.DatabaseUserDetailsService;
import com.example.gateway.security.JwtService;
import com.example.gateway.security.SecurityErrorHandler;
import com.example.gateway.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtConfig.class, DatabaseUserDetailsService.class,
        AuthService.class, JwtService.class, SecurityErrorHandler.class,
        AuthControllerTest.ProtectedTestController.class})
class AuthControllerTest {

    private static final String SECRET = TestJwtSecrets.randomSecret();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.secret", () -> SECRET);
        registry.add("security.jwt.issuer", () -> "gateway-api");
        registry.add("security.cors.allowed-origins", () -> "http://localhost:5173,https://client.example");
    }

    @BeforeEach
    void configureUser() {
        when(userAccountRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userAccountRepository.findByUsername("usuario")).thenReturn(Optional.of(
                new UserAccount("usuario", passwordEncoder.encode("senha123"), Set.of("USER"))));
    }

    @Test
    void rejectsMalformedPathsWithTheJsonErrorContractBeforeAuthentication() throws Exception {
        mvc.perform(get(java.net.URI.create("/api/orders//items")))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "INVALID_REQUEST", "/api/orders//items"))
                .andExpect(jsonPath("$.message").value("Request URL or headers are invalid"));
        verifyNoInteractions(userAccountRepository);
    }

    @Test
    void authenticatesAgainstBcryptAndIssuesAnExactlyOneHourJwt() throws Exception {
        String response = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"usuario","password":"senha123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andReturn().getResponse().getContentAsString();
        Jwt jwt = jwtDecoder.decode(objectMapper.readTree(response).get("token").asText());
        assertThat(jwt.getSubject()).isEqualTo("usuario");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("gateway-api");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds()).isEqualTo(3600);
        assertThat(jwt.getClaims()).doesNotContainKeys("password", "password_hash");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/login", "/api/orders", "/api/orders/1/items/2"})
    void acceptsConfiguredPreflightWithoutAuthenticationOrDatabaseAccess(String path) throws Exception {
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,HEAD,POST,PUT,DELETE,OPTIONS"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization, content-type"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        verifyNoInteractions(userAccountRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "https://client.example"})
    void allowsLoginFromEachConfiguredOrigin(String origin) throws Exception {
        mvc.perform(post("/auth/login").header(HttpHeaders.ORIGIN, origin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"usuario\",\"password\":\"senha123\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void keepsActualOrdersRequestsProtectedAfterASuccessfulPreflight() throws Exception {
        mvc.perform(get("/api/orders/security-check").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(errorContract(401, "UNAUTHORIZED", "/api/orders/security-check"));
        verifyNoInteractions(userAccountRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://untrusted.example", "http://localhost:5174", "null"})
    void rejectsUnconfiguredOriginsBeforeAuthenticating(String origin) throws Exception {
        mvc.perform(options("/api/orders").header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(post("/auth/login").header(HttpHeaders.ORIGIN, origin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"usuario\",\"password\":\"senha123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        verifyNoInteractions(userAccountRepository);
    }

    @Test
    void rejectsUnconfiguredCorsMethodsHeadersAndPaths() throws Exception {
        mvc.perform(options("/api/orders").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/orders").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Untrusted"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/unconfigured").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        // An ordinary OPTIONS request is still protected; only a valid preflight bypasses JWT.
        mvc.perform(options("/api/orders")).andExpect(status().isUnauthorized());
        verifyNoInteractions(userAccountRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cookie", "query"})
    void ignoresBearerTokensSuppliedThroughCookiesOrQueryParameters(String location) throws Exception {
        String token = loginToken();
        var incoming = get("/api/orders/security-check");
        if (location.equals("cookie")) {
            incoming.cookie(new Cookie("JWT", token));
        } else {
            incoming.queryParam("access_token", token);
        }
        mvc.perform(incoming).andExpect(status().isUnauthorized());
    }

    @Test
    void appliesDefaultSecurityHeadersAndHstsOnlyForSecureRequests() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mvc.perform(get("/api/orders").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("Strict-Transport-Security"));
    }

    @ParameterizedTest
    @CsvSource({"usuario,wrong-password", "unknown,senha123"})
    void rejectsIncorrectPasswordsAndUnknownUsersWithTheSameError(String username,
                                                                 String password) throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "INVALID_CREDENTIALS", "/auth/login"))
                .andExpect(jsonPath("$.message").value("Invalid username or password"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void rejectsPasswordsLongerThanBcryptSupportsWithoutAnInternalError() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "usuario", "password", "é".repeat(37)))))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "INVALID_CREDENTIALS", "/auth/login"));
        verifyNoInteractions(userAccountRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"username\":\" \",\"password\":\"senha123\"}",
            "{\"username\":\"usuario\",\"password\":\"\"}"
    })
    void rejectsInvalidInputBeforeLookingUpAUser(String body) throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "VALIDATION_ERROR", "/auth/login"))
                .andExpect(jsonPath("$.errors").isMap());
        verifyNoInteractions(userAccountRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"username\":\"usuario\",\"password\":\"senha123\",\"roles\":[\"ADMIN\"]}"})
    void rejectsMalformedJsonAndClientSuppliedRoles(String body) throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "INVALID_REQUEST", "/auth/login"));
        verifyNoInteractions(userAccountRepository);
    }

    @Test
    void allowsLoginEvenWhenTheClientSendsAnOldInvalidBearer() throws Exception {
        mvc.perform(post("/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-old-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"usuario","password":"senha123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/orders/10", "/api/orders/10/items/20",
            "/unconfigured", "/auth/login", "/logout"})
    void requiresBearerAuthenticationForOrdersAndOtherRoutes(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(errorContract(401, "UNAUTHORIZED", path));
    }

    @Test
    void validatesTheIssuedBearerAndMapsItsRolesWithoutCreatingASession() throws Exception {
        String token = loginToken();
        mvc.perform(get("/api/orders/security-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("usuario"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        // The next request does not inherit the authentication from the previous request.
        mvc.perform(get("/api/orders/security-check")).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsAuthenticatedBearerWritesWithoutACsrfToken() throws Exception {
        mvc.perform(post("/api/orders/security-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("usuario"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "wrong-issuer", "missing-exp", "missing-sub",
            "missing-iat", "future-iat", "tampered", "different-secret", "future"})
    void rejectsInvalidBearerTokens(String scenario) throws Exception {
        Instant now = Instant.now();
        Instant issuedAt = scenario.equals("expired") ? now.minusSeconds(3600) : now.minusSeconds(1);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(scenario.equals("wrong-issuer") ? "another-api" : "gateway-api")
                .notBefore(issuedAt)
                .claim("roles", List.of("USER"));
        if (!scenario.equals("missing-iat")) {
            claims.issuedAt(scenario.equals("future-iat") ? now.plusSeconds(60) : issuedAt);
        }
        if (!scenario.equals("missing-sub")) {
            claims.subject("usuario");
        }
        if (!scenario.equals("missing-exp")) {
            claims.expiresAt(scenario.equals("expired") ? now.minusSeconds(10) : now.plusSeconds(3600));
        }
        if (scenario.equals("future")) {
            claims.notBefore(now.plusSeconds(60));
        }
        JwtEncoder encoder = jwtEncoder;
        if (scenario.equals("different-secret")) {
            JwtConfig config = new JwtConfig();
            encoder = config.jwtEncoder(config.jwtSecretKey(TestJwtSecrets.randomSecret()));
        }
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        if (scenario.equals("tampered")) {
            int signature = token.lastIndexOf('.') + 1;
            char replacement = token.charAt(signature) == 'A' ? 'B' : 'A';
            token = token.substring(0, signature) + replacement + token.substring(signature + 1);
        }
        mvc.perform(get("/api/orders/security-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "UNAUTHORIZED", "/api/orders/security-check"))
                .andExpect(jsonPath("$.message").value("Authentication is required or invalid"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/unconfigured", "/auth/login", "/api/orders-other", "/logout"})
    void deniesAuthenticatedRequestsOutsideExplicitlyAllowedRoutes(String path) throws Exception {
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isForbidden())
                .andExpect(errorContract(403, "FORBIDDEN", path))
                .andExpect(jsonPath("$.message").value("Access to this resource is denied"));
    }

    @Test
    void requiresAuthenticationForOrdersWithoutInventingAUserRoleRestriction() throws Exception {
        when(userAccountRepository.findByUsername("usuario")).thenReturn(Optional.of(
                new UserAccount("usuario", passwordEncoder.encode("senha123"), Set.of("AUDITOR"))));
        mvc.perform(get("/api/orders/security-check")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ROLE_AUDITOR"));
    }

    @Test
    void rejectsUnsignedTokensEvenWithOtherwiseValidClaims() throws Exception {
        Instant now = Instant.now();
        String unsigned = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                objectMapper.writeValueAsBytes(Map.of("alg", "none", "typ", "JWT")))
                + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                objectMapper.writeValueAsBytes(Map.of("iss", "gateway-api", "sub", "usuario",
                        "iat", now.getEpochSecond(), "exp", now.plusSeconds(3600).getEpochSecond(),
                        "roles", List.of("USER")))) + ".";
        mvc.perform(get("/api/orders/security-check")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unsigned))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "UNAUTHORIZED", "/api/orders/security-check"));
    }

    @Test
    void rejectsMalformedBearerTokens() throws Exception {
        mvc.perform(get("/api/orders/security-check")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "UNAUTHORIZED", "/api/orders/security-check"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsAuthenticationOutcomesWithoutCredentialsTokensOrTheSigningSecret(CapturedOutput output) throws Exception {
        String token = loginToken();
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"unknown-user\",\"password\":\"private-password-marker\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(output.getAll()).contains("event=AUTHENTICATION_SUCCEEDED", "event=AUTHENTICATION_FAILED")
                .doesNotContain("senha123", "private-password-marker", "unknown-user", token, SECRET);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void hidesSensitiveInternalErrorsFromResponsesAndLogs(CapturedOutput output) throws Exception {
        when(userAccountRepository.findByUsername("usuario"))
                .thenThrow(new IllegalStateException("password=sensitive-value token=secret-token"));
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"usuario","password":"senha123"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(errorContract(500, "INTERNAL_ERROR", "/auth/login"))
                .andExpect(jsonPath("$.message").value("An unexpected internal error occurred"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("sensitive-value", "secret-token", "senha123");
        assertThat(output.getAll()).doesNotContain("sensitive-value", "secret-token", "senha123");
    }

    private String loginToken() throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"usuario","password":"senha123"}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private ResultMatcher errorContract(int code, String errorCode, String path) {
        return result -> {
            content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
            jsonPath("$.timestamp").isString().match(result);
            jsonPath("$.status").value(code).match(result);
            jsonPath("$.code").value(errorCode).match(result);
            jsonPath("$.path").value(path).match(result);
            jsonPath("$.trace").doesNotExist().match(result);
            jsonPath("$.exception").doesNotExist().match(result);
        };
    }

    // These endpoints exist only in the test context to inspect the authenticated principal.
    @RestController
    static class ProtectedTestController {

        @GetMapping("/api/orders/security-check")
        Map<String, Object> currentUser(Authentication authentication) {
            return Map.of("username", authentication.getName(), "roles", authentication.getAuthorities()
                    .stream().map(GrantedAuthority::getAuthority).toList());
        }

        @PostMapping("/api/orders/security-check")
        String write(Authentication authentication) {
            return authentication.getName();
        }

    }
}
