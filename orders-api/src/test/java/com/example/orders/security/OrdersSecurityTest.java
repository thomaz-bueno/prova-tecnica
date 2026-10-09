package com.example.orders.security;

import com.example.orders.config.JwtConfig;
import com.example.orders.config.SecurityConfig;
import com.example.orders.controller.OrderController;
import com.example.orders.controller.OrderItemController;
import com.example.orders.dto.OrderResponse;
import com.example.orders.entity.OrderStatus;
import com.example.orders.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {OrderController.class, OrderItemController.class})
@Import({SecurityConfig.class, JwtConfig.class, SecurityErrorHandler.class})
class OrdersSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService service;

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.secret", () -> JwtTestTokens.SECRET);
        registry.add("security.jwt.issuer", () -> JwtTestTokens.ISSUER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/orders/1", "/api/orders/1/items", "/api/orders/1/items/2", "/logout"})
    void rejectsReadsWithoutATokenBeforeCallingTheService(String path) throws Exception {
        mvc.perform(get(path)).andExpect(securityError(401, "UNAUTHORIZED", path))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedPathsWithTheJsonErrorContractBeforeAuthentication() throws Exception {
        mvc.perform(get(java.net.URI.create("/api/orders//items")))
                .andExpect(securityError(400, "INVALID_REQUEST", "/api/orders//items"))
                .andExpect(jsonPath("$.message").value("Request URL or headers are invalid"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsWritesWithoutAToken() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Alice","customerEmail":"alice@example.com"}
                                """))
                .andExpect(securityError(401, "UNAUTHORIZED", "/api/orders"));
        mvc.perform(put("/api/orders/1/items/2").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productName":"Keyboard","quantity":1,"unitPrice":10}
                                """))
                .andExpect(securityError(401, "UNAUTHORIZED", "/api/orders/1/items/2"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "AUDITOR"})
    void acceptsGatewayFormatTokensAndConvertsRolesWithoutAddingARoleRestriction(String role) throws Exception {
        when(service.getOrders(any(Pageable.class))).thenAnswer(invocation -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getName()).isEqualTo("usuario");
            assertThat(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList())
                    .containsExactly("ROLE_" + role);
            return Page.empty();
        });
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + JwtTestTokens.validToken(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    void acceptsAuthenticatedWritesWithoutCsrfAndDoesNotKeepASession() throws Exception {
        when(service.createOrder(any())).thenReturn(new OrderResponse(1L, "Alice", "alice@example.com",
                LocalDateTime.now(), OrderStatus.PENDING, List.of(), new BigDecimal("0.00")));
        var result = mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + JwtTestTokens.validToken("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Alice","customerEmail":"alice@example.com"}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(1))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        mvc.perform(get("/api/orders")).andExpect(securityError(401, "UNAUTHORIZED", "/api/orders"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "wrong-issuer", "missing-sub", "missing-exp", "missing-iat",
            "future-iat", "future-nbf", "different-secret", "tampered", "unsigned", "malformed"})
    void rejectsInvalidTokensBeforeCallingTheService(String scenario) throws Exception {
        Instant now = Instant.now();
        Instant issuedAt = scenario.equals("expired") ? now.minusSeconds(3600) : now.minusSeconds(1);
        var claims = JwtClaimsSet.builder()
                .issuer(scenario.equals("wrong-issuer") ? "another-api" : JwtTestTokens.ISSUER)
                .notBefore(scenario.equals("future-nbf") ? now.plusSeconds(60) : issuedAt)
                .claim("roles", List.of("USER"));
        if (!scenario.equals("missing-sub")) {
            claims.subject("usuario");
        }
        if (!scenario.equals("missing-iat")) {
            claims.issuedAt(scenario.equals("future-iat") ? now.plusSeconds(60) : issuedAt);
        }
        if (!scenario.equals("missing-exp")) {
            claims.expiresAt(scenario.equals("expired") ? now.minusSeconds(10) : now.plusSeconds(3600));
        }
        String token = JwtTestTokens.sign(claims.build(), scenario.equals("different-secret")
                ? JwtTestTokens.randomSecret() : JwtTestTokens.SECRET);
        if (scenario.equals("tampered")) {
            int signature = token.lastIndexOf('.') + 1;
            token = token.substring(0, signature) + (token.charAt(signature) == 'A' ? 'B' : 'A')
                    + token.substring(signature + 1);
        } else if (scenario.equals("unsigned")) {
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(Map.of("alg", "none", "typ", "JWT")))
                    + token.substring(token.indexOf('.'), token.lastIndexOf('.') + 1);
        } else if (scenario.equals("malformed")) {
            token = "not-a-jwt";
        }
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(securityError(401, "UNAUTHORIZED", "/api/orders"))
                .andExpect(jsonPath("$.message").value("Authentication is required or invalid"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsHttpBasicInsteadOfAuthenticatingIt() throws Exception {
        String credentials = Base64.getEncoder().encodeToString("usuario:senha123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Basic " + credentials))
                .andExpect(securityError(401, "UNAUTHORIZED", "/api/orders"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cookie", "query"})
    void requiresBearerInTheHeaderInsteadOfCookiesOrQueryParameters(String location) throws Exception {
        String token = JwtTestTokens.validToken("USER");
        var incoming = get("/api/orders");
        if (location.equals("cookie")) {
            incoming.cookie(new Cookie("JWT", token));
        } else {
            incoming.queryParam("access_token", token);
        }
        mvc.perform(incoming).andExpect(securityError(401, "UNAUTHORIZED", "/api/orders"));
        verifyNoInteractions(service);
    }

    @Test
    void retainsSecurityHeadersWithoutGrantingBrowserCorsAccess() throws Exception {
        mvc.perform(get("/api/orders").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(securityError(401, "UNAUTHORIZED", "/api/orders"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        when(service.getOrders(any(Pageable.class))).thenReturn(Page.empty());
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.validToken("USER"))
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/login", "/v3/api-docs", "/api/orders-other", "/logout"})
    void deniesAuthenticatedAccessOutsideOrdersIncludingLogin(String path) throws Exception {
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.validToken("USER")))
                .andExpect(securityError(403, "FORBIDDEN", path));
        verifyNoInteractions(service);
    }

    private ResultMatcher securityError(int code, String errorCode, String path) {
        return result -> {
            status().is(code).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
            jsonPath("$.timestamp").isString().match(result);
            jsonPath("$.status").value(code).match(result);
            jsonPath("$.code").value(errorCode).match(result);
            jsonPath("$.path").value(path).match(result);
            jsonPath("$.trace").doesNotExist().match(result);
            jsonPath("$.exception").doesNotExist().match(result);
        };
    }
}
