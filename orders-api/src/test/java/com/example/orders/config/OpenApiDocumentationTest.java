package com.example.orders.config;

import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.security.SecureRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import com.example.orders.controller.OrderController;
import com.example.orders.controller.OrderItemController;
import com.example.orders.service.OrderService;
import com.example.orders.security.SecurityErrorHandler;

@WebMvcTest(controllers = {OrderController.class, OrderItemController.class})
@Import({OpenApiConfig.class, SecurityConfig.class, JwtConfig.class, SecurityErrorHandler.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, SwaggerConfig.class,
        SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class})
class OpenApiDocumentationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean OrderService service;
    private static final String SECRET = secret();

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.secret", () -> SECRET);
        registry.add("security.jwt.issuer", () -> "gateway-api");
    }

    @Test
    void documentsAllTenOperationsWithActualDtoSchemasAndHttpStatuses() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ApiErrorResponse.properties.errors").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.components.schemas.OrderResponse.properties.totalAmount").exists())
                .andExpect(jsonPath("$.components.schemas.CreateOrderRequest.required").isArray())
                .andReturn().getResponse().getContentAsString();
        var paths = mapper.readTree(response).get("paths");
        String[][] routes = {
                {"/api/orders", "get", "200"}, {"/api/orders", "post", "201"},
                {"/api/orders/{id}", "get", "200"}, {"/api/orders/{id}", "put", "200"},
                {"/api/orders/{id}", "delete", "204"},
                {"/api/orders/{orderId}/items", "get", "200"}, {"/api/orders/{orderId}/items", "post", "201"},
                {"/api/orders/{orderId}/items/{itemId}", "get", "200"},
                {"/api/orders/{orderId}/items/{itemId}", "put", "200"},
                {"/api/orders/{orderId}/items/{itemId}", "delete", "204"}};
        assertThat(paths.size()).isEqualTo(4);
        for (String[] route : routes) {
            var operation = paths.path(route[0]).path(route[1]);
            assertThat(operation.path("summary").asText()).isNotBlank();
            for (String code : new String[]{route[2], "400", "401", "403", "500"}) {
                assertThat(operation.path("responses").has(code)).as("%s %s response %s", route[1], route[0], code).isTrue();
            }
            if (route[0].contains("{")) assertThat(operation.path("responses").has("404")).isTrue();
            if (route[2].equals("204")) assertThat(operation.path("responses").path("204").has("content")).isFalse();
            if (route[1].equals("post") || route[1].equals("put")) {
                assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").has("$ref")).isTrue();
            }
        }
        assertThat(paths.path("/api/orders").path("get").path("parameters").size()).isEqualTo(2);
        assertThat(paths.path("/api/orders").path("get").path("responses").path("200").toString()).contains("PageResponse");
        assertThat(mapper.readTree(response).path("components").path("schemas").path("CreateOrderRequest").path("properties").has("totalAmount")).isFalse();
    }

    @Test
    void servesSwaggerResourcesWithoutExposingProtectedOrders() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        mvc.perform(get("/swagger-ui/swagger-ui.css")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"))
                .andExpect(jsonPath("$.persistAuthorization").value(false));
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
