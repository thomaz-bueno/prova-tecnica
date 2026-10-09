package com.example.gateway.controller;

import com.example.gateway.TestJwtSecrets;
import com.example.gateway.config.JwtConfig;
import com.example.gateway.config.OrdersWebClientConfig;
import com.example.gateway.config.SecurityConfig;
import com.example.gateway.entity.UserAccount;
import com.example.gateway.repository.UserAccountRepository;
import com.example.gateway.security.DatabaseUserDetailsService;
import com.example.gateway.security.JwtService;
import com.example.gateway.security.SecurityErrorHandler;
import com.example.gateway.service.AuthService;
import com.example.gateway.service.OrdersProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.net.ConnectException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, OrdersProxyController.class})
@Import({SecurityConfig.class, JwtConfig.class, DatabaseUserDetailsService.class,
        AuthService.class, JwtService.class, SecurityErrorHandler.class,
        OrdersWebClientConfig.class, OrdersProxyService.class,
        OrdersProxyControllerTest.TransportConfig.class})
class OrdersProxyControllerTest {

    private static final String SECRET = TestJwtSecrets.randomSecret();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestTransport transport;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.secret", () -> SECRET);
        registry.add("security.jwt.issuer", () -> "gateway-api");
        registry.add("orders.api.url", () -> "http://orders.test:8081");
        registry.add("orders.api.connect-timeout", () -> "100ms");
        registry.add("orders.api.timeout", () -> "500ms");
    }

    @BeforeEach
    void resetTransport() {
        transport.reset();
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/orders", "GET,/api/orders/10", "POST,/api/orders",
            "PUT,/api/orders/10", "DELETE,/api/orders/10",
            "GET,/api/orders/10/items", "POST,/api/orders/10/items",
            "GET,/api/orders/10/items/20", "PUT,/api/orders/10/items/20",
            "DELETE,/api/orders/10/items/20"
    })
    void forwardsAllRequiredRoutesWithTheirMethodBodyAndValidatedBearer(String method, String path)
            throws Exception {
        String token = validToken();
        byte[] body = method.equals("POST") || method.equals("PUT")
                ? "{\"productName\":\"Café\",\"quantity\":2}".getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        var incoming = request(HttpMethod.valueOf(method), URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON);
        if (body.length > 0) {
            incoming.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mvc.perform(incoming).andExpect(status().isOk())
                .andExpect(cookie().doesNotExist("JSESSIONID"));

        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.sent.method()).isEqualTo(HttpMethod.valueOf(method));
        assertThat(transport.sent.url()).isEqualTo(URI.create("http://orders.test:8081" + path));
        assertThat(transport.sent.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
        assertThat(transport.sent.headers().getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/json");
        assertThat(transport.sent.body()).containsExactly(body);
        verifyNoInteractions(userAccountRepository);
    }

    @Test
    void preservesRawQueryIncludingPaginationEncodingAndRepeatedSortParameters() throws Exception {
        String query = "page=2&size=10&sort=id,desc&sort=customerName,asc&q=A%2BB%20C&value=x+y";
        mvc.perform(get(URI.create("/api/orders?" + query))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isOk());
        assertThat(transport.sent.url().toASCIIString()).isEqualTo("http://orders.test:8081/api/orders?" + query);
    }

    @Test
    void createsAnOrderThroughTheGatewayUsingTheTokenReturnedByLogin() throws Exception {
        when(userAccountRepository.findByUsername("usuario")).thenReturn(Optional.of(
                new UserAccount("usuario", passwordEncoder.encode("senha123"), Set.of("USER"))));
        String login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"usuario\",\"password\":\"senha123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).get("token").asText();
        String body = "{\"customerName\":\"Ana\",\"customerEmail\":\"ana@example.com\"}";
        String response = "{\"id\":42,\"customerName\":\"Ana\",\"status\":\"PENDING\",\"items\":[],\"totalAmount\":0}";
        transport.reply = () -> Mono.just(ClientResponse.create(HttpStatusCode.valueOf(201))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.LOCATION, "http://orders.test:8081/api/orders/42")
                .body(response).build());

        mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType("application/json;charset=UTF-8").content(body))
                .andExpect(status().isCreated())
                .andExpect(content().string(response))
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/orders/42"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Location"));
        assertThat(transport.sent.url()).isEqualTo(URI.create("http://orders.test:8081/api/orders"));
        assertThat(transport.sent.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
        assertThat(transport.sent.headers().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo("application/json;charset=UTF-8");
        assertThat(new String(transport.sent.body(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(transport.sent.headers()).doesNotContainKey(HttpHeaders.ORIGIN);
    }

    @Test
    void handlesPreflightInTheGatewayWithoutCallingOrders() throws Exception {
        mvc.perform(options("/api/orders/10/items").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        assertThat(transport.calls).isZero();
    }

    @Test
    void returnsNoContentAfterDeletionWithoutInventingABody() throws Exception {
        transport.reply = () -> Mono.just(ClientResponse.create(HttpStatusCode.valueOf(204)).build());
        mvc.perform(request(HttpMethod.DELETE, URI.create("/api/orders/10"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 409, 422, 500, 503})
    void preservesDownstreamErrorStatusBodyAndRelevantHeaders(int code) throws Exception {
        String body = "{\"status\":" + code + ",\"code\":\"ORDERS_ERROR\",\"message\":\"Orders response\"}";
        transport.reply = () -> Mono.just(ClientResponse.create(HttpStatusCode.valueOf(code))
                .header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .header(HttpHeaders.ALLOW, "GET, POST")
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .header(HttpHeaders.RETRY_AFTER, "10")
                .header(HttpHeaders.CONNECTION, "close")
                .header(HttpHeaders.SET_COOKIE, "upstream-session=should-not-be-forwarded")
                .body(body).build());
        mvc.perform(get("/api/orders/10").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().is(code)).andExpect(content().string(body))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string(HttpHeaders.ALLOW, "GET, POST"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(header().doesNotExist(HttpHeaders.CONNECTION))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid"})
    void rejectsUnauthenticatedRequestsBeforeCallingOrders(String scenario) throws Exception {
        var incoming = get("/api/orders");
        if (scenario.equals("invalid")) {
            incoming.header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt");
        }
        mvc.perform(incoming).andExpect(status().isUnauthorized());
        assertThat(transport.calls).isZero();
    }

    @Test
    void forwardsOnlySelectedRequestHeaders() throws Exception {
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                        .header(HttpHeaders.HOST, "client.example")
                        .header(HttpHeaders.COOKIE, "session=private")
                        .header(HttpHeaders.CONNECTION, "close")
                        .header("X-Untrusted", "client-value"))
                .andExpect(status().isOk());
        assertThat(transport.sent.headers()).doesNotContainKeys(
                HttpHeaders.HOST, HttpHeaders.COOKIE, HttpHeaders.CONNECTION, "X-Untrusted");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void returnsSafeBadGatewayWhenOrdersCannotBeReached(CapturedOutput output) throws Exception {
        String token = validToken();
        transport.reply = () -> Mono.error(new WebClientRequestException(
                new ConnectException("private infrastructure details " + token), HttpMethod.GET,
                URI.create("http://orders.test:8081/api/orders"), transport.sent.headers()));
        String response = mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ORDERS_API_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Orders API is unavailable"))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(token, "private infrastructure details");
        assertThat(output.getAll()).contains("event=ORDERS_API_COMMUNICATION_FAILED status=502")
                .doesNotContain(token, "private infrastructure details");
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsUpstreamServerErrorsWithoutLoggingTheResponseBody(CapturedOutput output) throws Exception {
        String body = "{\"message\":\"private-upstream-body-marker\"}";
        transport.reply = () -> Mono.just(ClientResponse.create(HttpStatusCode.valueOf(503))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build());
        String token = validToken();
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json(body));
        assertThat(output.getAll()).contains("event=ORDERS_API_SERVER_ERROR status=503")
                .doesNotContain("private-upstream-body-marker", token);
    }

    @Test
    void returnsGatewayTimeoutWhenTheWholeExchangeTakesTooLong() throws Exception {
        transport.reply = Mono::never;
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("ORDERS_API_TIMEOUT"))
                .andExpect(jsonPath("$.message").value("Orders API request timed out"));
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test
    void treatsConnectionTimeoutAsGatewayTimeout() throws Exception {
        transport.reply = () -> Mono.error(new WebClientRequestException(
                new io.netty.channel.ConnectTimeoutException("connection took too long"), HttpMethod.GET,
                URI.create("http://orders.test:8081/api/orders"), new HttpHeaders()));
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("ORDERS_API_TIMEOUT"));
    }

    @Test
    void returnsBadGatewayWhenTheResponseExceedsTheDecodingLimit() throws Exception {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)).build();
        DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();
        transport.reply = () -> Mono.just(ClientResponse.create(HttpStatusCode.valueOf(200), strategies)
                .body(Flux.just(buffers.wrap(new byte[1024 * 1024]),
                        buffers.wrap(new byte[1024 * 1024 + 1]))).build());
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ORDERS_API_BAD_RESPONSE"));
    }

    @Test
    void returnsBadGatewayWhenTheConnectionBreaksWhileReadingTheBody() throws Exception {
        transport.reply = () -> Mono.just(ClientResponse.create(HttpStatusCode.valueOf(200))
                .body(Flux.error(new IOException("connection interrupted while reading"))).build());
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ORDERS_API_UNAVAILABLE"));
    }

    private String validToken() {
        return jwtService.generateToken(UsernamePasswordAuthenticationToken.authenticated(
                "usuario", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransportConfig {

        @Bean
        TestTransport testTransport() {
            return new TestTransport();
        }

        @Bean
        WebClient.Builder webClientBuilder(TestTransport transport) {
            return WebClient.builder().exchangeFunction(transport);
        }
    }

    // Only the external HTTP transport is replaced. WebClient still encodes requests and decodes responses.
    static class TestTransport implements ExchangeFunction {
        private int calls;
        private SentRequest sent;
        private Supplier<Mono<ClientResponse>> reply;

        void reset() {
            calls = 0;
            sent = null;
            reply = () -> Mono.just(ClientResponse.create(HttpStatusCode.valueOf(200)).build());
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            calls++;
            MockClientHttpRequest outgoing = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(outgoing, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(() -> DataBufferUtils.join(outgoing.getBody())
                            .map(buffer -> {
                                byte[] body = new byte[buffer.readableByteCount()];
                                buffer.read(body);
                                DataBufferUtils.release(buffer);
                                return body;
                            }).defaultIfEmpty(new byte[0])))
                    .flatMap(body -> {
                        sent = new SentRequest(request.method(), request.url(), outgoing.getHeaders(), body);
                        return reply.get();
                    });
        }
    }

    record SentRequest(HttpMethod method, URI url, HttpHeaders headers, byte[] body) {
    }
}
