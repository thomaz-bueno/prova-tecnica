package com.example.gateway.config;

import com.example.gateway.dto.ApiErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gatewayOpenAPI() {
        Components components = new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
        return new OpenAPI().components(components)
                .info(new Info().title("Gateway API").version("1.0.0").description("""
                        Login and authenticated proxy to Orders. All /api/orders and /api/orders/** routes
                        are forwarded with the original method, path, query, body and validated Bearer token.
                        This UI shows two executable proxy examples, not the complete Orders contract.
                        GET/PUT/DELETE /api/orders/{id} and item list/create/read/update/delete routes are also proxied.
                        See the complete contract at http://localhost:8081/swagger-ui.html in local development.
                        The proxy has no Order DTOs: its JSON schemas are deliberately generic.
                        Upstream 4xx/5xx statuses and bodies are preserved; connection failures produce 502,
                        timeouts produce 504. Obtain a token at /auth/login and paste only the token into Authorize.
                        """))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    public OpenApiCustomizer gatewayDocumentation() {
        return api -> {
            ModelConverters.getInstance().readAll(ApiErrorResponse.class).forEach(api.getComponents()::addSchemas);
            Operation login = api.getPaths().get("/auth/login").getPost();
            login.setSummary("Authenticate and obtain a JWT valid for exactly one hour");
            login.setTags(List.of("Authentication"));
            login.setSecurity(List.of());
            login.getRequestBody().getContent().values().forEach(media -> media.setExample(
                    Map.of("username", "usuario", "password", "senha123")));
            ApiResponse loginSuccess = login.getResponses().get("200");
            loginSuccess.setContent(new Content().addMediaType("application/json",
                    loginSuccess.getContent().values().iterator().next()));
            login.getResponses().get("200").setDescription("Bearer token; expiresIn = 3600 seconds; response is not cached");
            login.getResponses().get("200").getContent().values().forEach(media -> media.setExample(
                    Map.of("token", "<JWT>", "tokenType", "Bearer", "expiresIn", 3600)));
            login.getResponses().addApiResponse("400", error("Invalid login request"))
                    .addApiResponse("401", error("Invalid username or password"))
                    .addApiResponse("500", error("Internal error; no sensitive details"));

            Operation list = proxy("listOrdersViaGateway", "List orders through the proxy", "200");
            list.addParametersItem(new Parameter().name("page").in("query").description("Zero-based page index")
                    .schema(new IntegerSchema()._default(0).minimum(BigDecimal.ZERO)));
            list.addParametersItem(new Parameter().name("size").in("query").description("Page size, 1–100")
                    .schema(new IntegerSchema()._default(10).minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100))));
            Operation create = proxy("createOrderViaGateway", "Create an order through the proxy", "201");
            create.setRequestBody(new RequestBody().required(true).description("Orders create request; see Orders contract")
                    .content(json(new ObjectSchema().additionalProperties(true),
                            Map.of("customerName", "Ana", "customerEmail", "ana@example.com"))));
            api.getPaths().addPathItem("/api/orders", new PathItem().get(list).post(create));
            Schema<?> password = (Schema<?>) api.getComponents().getSchemas().get("LoginRequest").getProperties().get("password");
            password.setWriteOnly(true);
            password.setFormat("password");
        };
    }

    private Operation proxy(String id, String summary, String successStatus) {
        return new Operation().operationId(id).summary(summary).tags(List.of("Orders proxy"))
                .description("Forwards the validated Bearer JWT to Orders, which validates it again. "
                        + "Response schemas are generic; the business contract is documented by Orders.")
                .responses(new ApiResponses()
                        .addApiResponse(successStatus, new ApiResponse().description("Orders response, unchanged")
                                .content(json(new ObjectSchema().additionalProperties(true), null)))
                        .addApiResponse("400", error("Invalid data or query parameters reported by Orders"))
                        .addApiResponse("401", error("Missing, invalid or expired token, rejected by either API"))
                        .addApiResponse("403", error("Authenticated request denied"))
                        .addApiResponse("500", error("Internal error from either API; upstream status/body preserved"))
                        .addApiResponse("502", error("Orders unavailable or unreadable response"))
                        .addApiResponse("504", error("Orders request timed out")));
    }

    private ApiResponse error(String description) {
        return new ApiResponse().description(description).content(json(
                new Schema<>().$ref("#/components/schemas/ApiErrorResponse"), null));
    }

    private Content json(Schema<?> schema, Object example) {
        return new Content().addMediaType("application/json", example == null ? new MediaType().schema(schema) : new MediaType().schema(schema).example(example));
    }
}
