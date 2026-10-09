package com.example.gateway.config;

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
import com.example.gateway.controller.AuthController;
import com.example.gateway.repository.UserAccountRepository;
import com.example.gateway.security.DatabaseUserDetailsService;
import com.example.gateway.security.JwtService;
import com.example.gateway.service.AuthService;
import com.example.gateway.security.SecurityErrorHandler;

@WebMvcTest(controllers = AuthController.class)
@Import({OpenApiConfig.class, SecurityConfig.class, JwtConfig.class, SecurityErrorHandler.class, DatabaseUserDetailsService.class, JwtService.class, AuthService.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, SwaggerConfig.class,
        SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class})
class OpenApiDocumentationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean UserAccountRepository repository;
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
    void documentsPublicLoginAndAuthenticatedProxyExamples() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ApiErrorResponse.properties.errors").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/auth/login'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/orders'].get.parameters[0].name").value("page"))
                .andExpect(jsonPath("$.paths['/api/orders'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/orders'].post.responses['502']").exists())
                .andExpect(jsonPath("$.paths['/api/orders'].post.responses['504']").exists())
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.password.writeOnly").value(true));
        var json = mapper.readTree(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        assertThat(json.get("paths").size()).isEqualTo(2);
        assertThat(json.get("paths").has("/api/orders/**")).isFalse();
        assertThat(json.toString()).doesNotContain("JWT_SECRET");
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
