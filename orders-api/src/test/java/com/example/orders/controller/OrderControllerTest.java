package com.example.orders.controller;

import com.example.orders.config.SecurityConfig;
import com.example.orders.config.JwtConfig;
import com.example.orders.dto.CreateOrderRequest;
import com.example.orders.dto.OrderItemRequest;
import com.example.orders.dto.OrderItemResponse;
import com.example.orders.dto.OrderResponse;
import com.example.orders.dto.OrderSummaryResponse;
import com.example.orders.dto.UpdateOrderRequest;
import com.example.orders.entity.OrderStatus;
import com.example.orders.exception.InvalidOrderAmountException;
import com.example.orders.exception.OrderItemNotFoundException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.security.SecurityErrorHandler;
import com.example.orders.security.JwtTestTokens;
import com.example.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {OrderController.class, OrderItemController.class})
@Import({SecurityConfig.class, JwtConfig.class, SecurityErrorHandler.class})
@WithMockUser(roles = "USER")
class OrderControllerTest {

    private static final String VALID_ORDER = """
            {"customerName":"Alice","customerEmail":"alice@example.com"}
            """;
    private static final String VALID_ITEM = """
            {"productName":"Keyboard","quantity":2,"unitPrice":19.90}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrderService service;

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.secret", () -> JwtTestTokens.SECRET);
        registry.add("security.jwt.issuer", () -> JwtTestTokens.ISSUER);
    }

    @Test
    void returnsPaginationMetadataAndSummaries() throws Exception {
        PageRequest pageable = PageRequest.of(1, 2, Sort.by("id"));
        var summary = new OrderSummaryResponse(1L, "Alice", "alice@example.com",
                LocalDateTime.of(2026, 10, 6, 12, 0), OrderStatus.PENDING, new BigDecimal("0.00"));
        when(service.getOrders(pageable)).thenReturn(new PageImpl<>(List.of(summary), pageable, 5));

        mvc.perform(get("/api/orders").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].items").doesNotExist());
        verify(service).getOrders(pageable);
    }

    @Test
    void defaultsPaginationToTheFirstTenOrders() throws Exception {
        PageRequest pageable = PageRequest.of(0, 10, Sort.by("id"));
        when(service.getOrders(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        mvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));
        verify(service).getOrders(pageable);
    }

    @Test
    void returnsOrderDetailsAsADto() throws Exception {
        when(service.getOrder(1L)).thenReturn(orderResponse());

        mvc.perform(get("/api/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.items[0].id").value(7))
                .andExpect(jsonPath("$.items[0].order").doesNotExist());
    }

    @Test
    void createsAnOrderAndReturnsItsLocation() throws Exception {
        when(service.createOrder(any(CreateOrderRequest.class))).thenReturn(orderResponse());

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/orders/1"))
                .andExpect(jsonPath("$.id").value(1));
        verify(service).createOrder(new CreateOrderRequest("Alice", "alice@example.com"));
    }

    @Test
    void updatesCustomerAndStatus() throws Exception {
        when(service.updateOrder(eq(1L), any(UpdateOrderRequest.class))).thenReturn(orderResponse());

        mvc.perform(put("/api/orders/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Bob","customerEmail":"bob@example.com","status":"DELIVERED"}
                                """))
                .andExpect(status().isOk());
        verify(service).updateOrder(1L,
                new UpdateOrderRequest("Bob", "bob@example.com", OrderStatus.DELIVERED));
    }

    @Test
    void deletesAnOrderWithoutAResponseBody() throws Exception {
        mvc.perform(delete("/api/orders/1"))
                .andExpect(status().isNoContent());
        verify(service).deleteOrder(1L);
    }

    @Test
    void listsItemsOfTheRequestedOrder() throws Exception {
        when(service.getItems(1L)).thenReturn(List.of(itemResponse()));

        mvc.perform(get("/api/orders/1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].order").doesNotExist());
    }

    @Test
    void returnsAnIndividualItem() throws Exception {
        when(service.getItem(1L, 7L)).thenReturn(itemResponse());

        mvc.perform(get("/api/orders/1/items/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(39.80))
                .andExpect(jsonPath("$.order").doesNotExist());
        verify(service).getItem(1L, 7L);
    }

    @Test
    void createsAnItemAndReturnsItsLocation() throws Exception {
        when(service.createItem(eq(1L), any(OrderItemRequest.class))).thenReturn(itemResponse());

        mvc.perform(post("/api/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ITEM))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/orders/1/items/7"))
                .andExpect(jsonPath("$.id").value(7));
        verify(service).createItem(1L,
                new OrderItemRequest("Keyboard", 2, new BigDecimal("19.90")));
    }

    @Test
    void updatesAnItemThroughItsParentPath() throws Exception {
        when(service.updateItem(eq(1L), eq(7L), any(OrderItemRequest.class))).thenReturn(itemResponse());

        mvc.perform(put("/api/orders/1/items/7")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ITEM))
                .andExpect(status().isOk());
        verify(service).updateItem(1L, 7L,
                new OrderItemRequest("Keyboard", 2, new BigDecimal("19.90")));
    }

    @Test
    void deletesAnItemWithoutAResponseBody() throws Exception {
        mvc.perform(delete("/api/orders/1/items/7"))
                .andExpect(status().isNoContent());
        verify(service).deleteItem(1L, 7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"customerName\":\"\",\"customerEmail\":\"alice@example.com\"}",
            "{\"customerName\":\"Alice\",\"customerEmail\":\"invalid\"}"
    })
    void rejectsInvalidOrdersBeforeCallingTheService(String body) throws Exception {
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "Bad Request", "VALIDATION_ERROR", "/api/orders"))
                .andExpect(jsonPath("$.errors").exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "orderDate", "status", "items", "totalAmount"})
    void rejectsServerControlledFieldsWhenCreatingAnOrder(String field) throws Exception {
        String body = "{\"customerName\":\"Alice\",\"customerEmail\":\"alice@example.com\",\""
                + field + "\":null}";

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"productName\":\"\",\"quantity\":2,\"unitPrice\":19.90}",
            "{\"productName\":\"Keyboard\",\"quantity\":0,\"unitPrice\":19.90}",
            "{\"productName\":\"Keyboard\",\"quantity\":-1,\"unitPrice\":19.90}",
            "{\"productName\":\"Keyboard\",\"quantity\":null,\"unitPrice\":19.90}",
            "{\"productName\":\"Keyboard\",\"quantity\":2,\"unitPrice\":0}",
            "{\"productName\":\"Keyboard\",\"quantity\":2,\"unitPrice\":-1}",
            "{\"productName\":\"Keyboard\",\"quantity\":2,\"unitPrice\":null}",
            "{\"productName\":\"Keyboard\",\"quantity\":2,\"unitPrice\":0.001}"
    })
    void rejectsInvalidItemsBeforeCallingTheService(String body) throws Exception {
        mvc.perform(post("/api/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "Bad Request", "VALIDATION_ERROR", "/api/orders/1/items"))
                .andExpect(jsonPath("$.errors").exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "subtotal", "order"})
    void rejectsServerControlledItemFields(String field) throws Exception {
        String body = "{\"productName\":\"Keyboard\",\"quantity\":2,\"unitPrice\":19.90,\""
                + field + "\":null}";

        mvc.perform(post("/api/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=invalid"})
    void rejectsInvalidPagination(String parameter) throws Exception {
        String[] parts = parameter.split("=");
        mvc.perform(get("/api/orders").param(parts[0], parts[1]))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsFractionalQuantitiesInsteadOfTruncatingThem() throws Exception {
        mvc.perform(post("/api/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productName":"Keyboard","quantity":2.5,"unitPrice":19.90}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid"})
    void rejectsInvalidOrderIds(String id) throws Exception {
        mvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsUnknownStatusValues() throws Exception {
        mvc.perform(put("/api/orders/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Alice","customerEmail":"alice@example.com","status":"UNKNOWN"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void reportsMissingOrdersAs404() throws Exception {
        when(service.getOrder(99L)).thenThrow(new OrderNotFoundException(99L));

        mvc.perform(get("/api/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(errorContract(404, "Not Found", "ORDER_NOT_FOUND", "/api/orders/99"))
                .andExpect(jsonPath("$.message").value("Order 99 not found"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void reportsAnItemOutsideTheRequestedOrderAs404() throws Exception {
        when(service.getItem(2L, 7L)).thenThrow(new OrderItemNotFoundException(2L, 7L));

        mvc.perform(get("/api/orders/2/items/7"))
                .andExpect(status().isNotFound())
                .andExpect(errorContract(404, "Not Found", "ORDER_ITEM_NOT_FOUND", "/api/orders/2/items/7"))
                .andExpect(jsonPath("$.message").value("Order item 7 not found in order 2"));
    }

    @Test
    void reportsCalculatedAmountOverflowAs400() throws Exception {
        when(service.createItem(eq(1L), any(OrderItemRequest.class)))
                .thenThrow(new InvalidOrderAmountException());

        mvc.perform(post("/api/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ITEM))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "Bad Request", "INVALID_ORDER_AMOUNT", "/api/orders/1/items"));
    }

    @Test
    @WithAnonymousUser
    void requiresAuthenticationForReads() throws Exception {
        mvc.perform(get("/api/orders/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "Unauthorized", "UNAUTHORIZED", "/api/orders/1"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(service);
    }

    @Test
    @WithAnonymousUser
    void requiresAuthenticationForWrites() throws Exception {
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "Unauthorized", "UNAUTHORIZED", "/api/orders"));
        verifyNoInteractions(service);
    }

    @Test
    void includesTheCustomerFieldsThatFailedValidation() throws Exception {
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":" ","customerEmail":"invalid"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "Bad Request", "VALIDATION_ERROR", "/api/orders"))
                .andExpect(jsonPath("$.errors.customerName").value("Customer name must not be blank"))
                .andExpect(jsonPath("$.errors.customerEmail").value("Customer email must be a valid email address"));
        verifyNoInteractions(service);
    }

    @Test
    void includesItemErrorsWhenMethodAndBodyValidationAreCombined() throws Exception {
        mvc.perform(post("/api/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productName":" ","quantity":0,"unitPrice":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "Bad Request", "VALIDATION_ERROR", "/api/orders/1/items"))
                .andExpect(jsonPath("$.errors.productName").value("Product name must not be blank"))
                .andExpect(jsonPath("$.errors.quantity").value("Quantity must be greater than zero"))
                .andExpect(jsonPath("$.errors.unitPrice").value("Unit price must be greater than zero"));
        verifyNoInteractions(service);
    }

    @Test
    void distinguishesMissingQuantityAndPriceFromNonpositiveValues() throws Exception {
        mvc.perform(post("/api/orders/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productName":"Keyboard","quantity":null,"unitPrice":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").value("Quantity is required"))
                .andExpect(jsonPath("$.errors.unitPrice").value("Unit price is required"));
        verifyNoInteractions(service);
    }

    @Test
    void validatesOrderUpdatesBeforeCallingTheService() throws Exception {
        mvc.perform(put("/api/orders/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"","customerEmail":"invalid","status":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "Bad Request", "VALIDATION_ERROR", "/api/orders/1"))
                .andExpect(jsonPath("$.errors.customerName").exists())
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andExpect(jsonPath("$.errors.status").value("Status is required"));
        verifyNoInteractions(service);
    }

    @Test
    void validatesItemUpdatesBeforeCallingTheService() throws Exception {
        mvc.perform(put("/api/orders/1/items/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productName":"","quantity":-1,"unitPrice":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "Bad Request", "VALIDATION_ERROR", "/api/orders/1/items/7"))
                .andExpect(jsonPath("$.errors.productName").exists())
                .andExpect(jsonPath("$.errors.quantity").exists())
                .andExpect(jsonPath("$.errors.unitPrice").exists());
        verifyNoInteractions(service);
    }

    @Test
    void reportsMalformedJsonWithoutParserDetails() throws Exception {
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(errorContract(400, "Bad Request", "INVALID_REQUEST", "/api/orders"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    void preservesTheAllowHeaderForUnsupportedMethods() throws Exception {
        mvc.perform(patch("/api/orders/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(errorContract(405, "Method Not Allowed", "METHOD_NOT_ALLOWED", "/api/orders/1"))
                .andExpect(header().string("Allow", containsString("GET")));
        verifyNoInteractions(service);
    }

    @Test
    void usesTheSameContractForUnsupportedContentTypes() throws Exception {
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.TEXT_PLAIN).content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(errorContract(415, "Unsupported Media Type", "UNSUPPORTED_MEDIA_TYPE", "/api/orders"));
        verifyNoInteractions(service);
    }

    @Test
    void usesTheSameContractForUnknownRoutes() throws Exception {
        mvc.perform(get("/api/orders/1/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(errorContract(404, "Not Found", "RESOURCE_NOT_FOUND", "/api/orders/1/unknown"));
        verifyNoInteractions(service);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void hidesSensitiveInternalErrorsFromTheResponseAndLogs(CapturedOutput output) throws Exception {
        when(service.getOrder(1L)).thenThrow(new IllegalStateException(
                "jdbc:postgresql://internal/orders?password=sensitive-test-value&token=secret-test-token"));

        String body = mvc.perform(get("/api/orders/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(errorContract(500, "Internal Server Error", "INTERNAL_ERROR", "/api/orders/1"))
                .andExpect(jsonPath("$.message").value("An unexpected internal error occurred"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("jdbc:", "sensitive-test-value", "secret-test-token",
                "IllegalStateException");
        assertThat(output.getAll()).doesNotContain("sensitive-test-value", "secret-test-token");
    }

    @Test
    @WithAnonymousUser
    void rejectsHttpBasicWithoutLeakingAuthenticationDetails() throws Exception {
        mvc.perform(get("/api/orders/1").with(httpBasic("invalid", "invalid")))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "Unauthorized", "UNAUTHORIZED", "/api/orders/1"))
                .andExpect(jsonPath("$.message").value("Authentication is required or invalid"));
        verifyNoInteractions(service);
    }

    @Test
    void handlesAuthorizationExceptionsRaisedInsideMvc() throws Exception {
        when(service.getOrder(1L)).thenThrow(new AccessDeniedException("Internal authorization detail"));

        mvc.perform(get("/api/orders/1"))
                .andExpect(status().isForbidden())
                .andExpect(errorContract(403, "Forbidden", "FORBIDDEN", "/api/orders/1"))
                .andExpect(jsonPath("$.message").value("Access to this resource is denied"));
    }

    @Test
    void handlesAuthenticationExceptionsRaisedInsideMvc() throws Exception {
        when(service.getOrder(1L)).thenThrow(new BadCredentialsException("Internal authentication detail"));

        mvc.perform(get("/api/orders/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(errorContract(401, "Unauthorized", "UNAUTHORIZED", "/api/orders/1"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("Authentication is required or invalid"));
    }

    private ResultMatcher errorContract(int statusCode, String error, String code, String path) {
        return result -> {
            content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
            jsonPath("$.timestamp").isString().match(result);
            jsonPath("$.status").value(statusCode).match(result);
            jsonPath("$.error").value(error).match(result);
            jsonPath("$.code").value(code).match(result);
            jsonPath("$.message").isString().match(result);
            jsonPath("$.path").value(path).match(result);
            jsonPath("$.trace").doesNotExist().match(result);
            jsonPath("$.exception").doesNotExist().match(result);
        };
    }

    private OrderItemResponse itemResponse() {
        return new OrderItemResponse(7L, "Keyboard", 2,
                new BigDecimal("19.90"), new BigDecimal("39.80"));
    }

    private OrderResponse orderResponse() {
        return new OrderResponse(1L, "Alice", "alice@example.com",
                LocalDateTime.of(2026, 10, 6, 12, 0), OrderStatus.PENDING,
                List.of(itemResponse()), new BigDecimal("39.80"));
    }
}
