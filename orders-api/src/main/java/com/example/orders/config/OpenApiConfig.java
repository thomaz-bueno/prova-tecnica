package com.example.orders.config;

import com.example.orders.dto.ApiErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
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
    public OpenAPI ordersOpenAPI() {
        Components components = new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
        return new OpenAPI().components(components)
                .info(new Info().title("Orders API").version("1.0.0").description("""
                        Order and item CRUD. Obtain a JWT from POST /auth/login on the gateway (port 8080).
                        Normal client traffic must use the gateway; this UI calls Orders directly for local diagnostics.
                        IDs, orderDate, subtotal and totalAmount are server managed. New orders are PENDING,
                        with no items and zero total. Item changes recalculate the order total.
                        Unknown request fields are rejected. Monetary values support at most two decimal places.
                        Item read/update/delete routes supplement the original endpoint list to provide full CRUD.
                        """))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    public OpenApiCustomizer ordersDocumentation() {
        Map<String, String> summaries = Map.ofEntries(
                Map.entry("getOrders", "List orders with pagination"),
                Map.entry("getOrder", "Find an order"),
                Map.entry("createOrder", "Create an empty pending order"),
                Map.entry("updateOrder", "Update customer details and status"),
                Map.entry("deleteOrder", "Delete an order and its items"),
                Map.entry("getItems", "List items belonging to an order"),
                Map.entry("getItem", "Find an item belonging to an order"),
                Map.entry("createItem", "Add an item and recalculate the total"),
                Map.entry("updateItem", "Update an item and recalculate the total"),
                Map.entry("deleteItem", "Delete an item and recalculate the total"));
        return api -> {
            ModelConverters.getInstance().readAll(ApiErrorResponse.class).forEach(api.getComponents()::addSchemas);
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                String id = operation.getOperationId();
                operation.setSummary(summaries.get(id));
                operation.setTags(List.of(path.contains("/items") ? "Order items" : "Orders"));
                documentResponses(operation, path);
                if ("getOrders".equals(id)) {
                    operation.setDescription("Page is zero based (default 0); size defaults to 10, range 1–100. "
                            + "Results are ordered by id. Returns content, page, size, totalElements and totalPages.");
                    operation.getParameters().forEach(parameter -> parameter.setDescription(
                            "page".equals(parameter.getName()) ? "Zero-based page index, minimum 0" : "Page size, from 1 to 100"));
                }
                if (operation.getRequestBody() != null) {
                    Object example = id.contains("Item")
                            ? Map.of("productName", "Keyboard", "quantity", 2, "unitPrice", new BigDecimal("49.90"))
                            : "updateOrder".equals(id)
                                ? Map.of("customerName", "Ana", "customerEmail", "ana@example.com", "status", "CONFIRMED")
                                : Map.of("customerName", "Ana", "customerEmail", "ana@example.com");
                    operation.getRequestBody().getContent().values().forEach(media -> media.setExample(example));
                }
            }));

        };
    }

    private void documentResponses(Operation operation, String path) {
        String id = operation.getOperationId();
        boolean create = id.startsWith("create");
        boolean delete = id.startsWith("delete");
        ApiResponse success = delete ? new ApiResponse() : operation.getResponses().get("200");
        if (success == null) success = operation.getResponses().get("201");
        if (success == null) success = new ApiResponse();
        if (success.getContent() != null && !success.getContent().isEmpty()) {
            success.setContent(new Content().addMediaType("application/json",
                    success.getContent().values().iterator().next()));
        }
        success.setDescription(delete ? "Deleted; empty response body" : create ? "Created" : "Success");
        if (create) success.addHeaderObject("Location", new Header().description("URI of the created resource")
                .schema(new StringSchema()));
        ApiResponses responses = new ApiResponses().addApiResponse(delete ? "204" : create ? "201" : "200", success);
        responses.addApiResponse("400", error("Invalid JSON, fields, path or pagination parameters; errors may list field failures"));
        if (!"getOrders".equals(id) && !"createOrder".equals(id)) {
            responses.addApiResponse("404", error(path.contains("/items")
                    ? "ORDER_NOT_FOUND or ORDER_ITEM_NOT_FOUND; item must belong to this order"
                    : "ORDER_NOT_FOUND"));
        }
        responses.addApiResponse("401", error("Missing, invalid or expired Bearer JWT"));
        responses.addApiResponse("403", error("Authenticated request denied by security rules"));
        responses.addApiResponse("500", error("Internal error; no sensitive details"));
        operation.setResponses(responses);
    }

    private ApiResponse error(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiErrorResponse"))));
    }
}
