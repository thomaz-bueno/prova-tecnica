package com.example.orders;

import com.example.orders.dto.CreateOrderRequest;
import com.example.orders.security.JwtTestTokens;
import com.example.orders.dto.OrderItemRequest;
import com.example.orders.repository.OrderItemRepository;
import com.example.orders.repository.OrderRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.example.orders.security.JwtTestTokens.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureMockMvc
class OrderPersistenceIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdOrderIds = new ArrayList<>();

    @DynamicPropertySource
    static void configureTestDatabase(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.secret", () -> JwtTestTokens.SECRET);
        registry.add("security.jwt.issuer", () -> JwtTestTokens.ISSUER);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("ORDERS_TEST_DB_URL",
                "jdbc:h2:mem:orders_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("ORDERS_TEST_DB_USERNAME", "sa"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("ORDERS_TEST_DB_PASSWORD", ""));
    }

    @AfterEach
    void removeCreatedOrders() {
        createdOrderIds.forEach(id ->
                orderRepository.findById(id).ifPresent(orderRepository::delete));
    }

    @Test
    void persistsTheCompleteCrudLifecycleAndCascadesOrderDeletion() throws Exception {
        long orderId = createOrder("Alice");
        JsonNode initialOrder = getOrder(orderId);
        assertThat(initialOrder.get("status").asText()).isEqualTo("PENDING");
        assertThat(initialOrder.get("orderDate").asText()).isNotBlank();
        assertThat(initialOrder.get("items").size()).isZero();
        assertTotal(initialOrder, "0.00");

        long keyboardId = createItem(orderId, "Keyboard", 2, "19.90");
        long mouseId = createItem(orderId, "Mouse", 1, "10.00");
        assertTotal(getOrder(orderId), "49.80");

        mvc.perform(get("/api/orders/{orderId}/items", orderId).with(bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/orders/{orderId}/items/{itemId}", orderId, keyboardId).with(bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(39.80));

        mvc.perform(put("/api/orders/{id}", orderId).with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Alice Updated","customerEmail":"updated@example.com","status":"DELIVERED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        assertThat(getOrder(orderId).get("orderDate")).isEqualTo(initialOrder.get("orderDate"));

        mvc.perform(put("/api/orders/{orderId}/items/{itemId}", orderId, keyboardId).with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderItemRequest("Keyboard Updated", 3, new BigDecimal("15.25")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(45.75));
        assertTotal(getOrder(orderId), "55.75");

        mvc.perform(delete("/api/orders/{orderId}/items/{itemId}", orderId, mouseId).with(bearer()))
                .andExpect(status().isNoContent());
        assertThat(orderItemRepository.findById(mouseId)).isEmpty();
        assertTotal(getOrder(orderId), "45.75");

        mvc.perform(delete("/api/orders/{orderId}/items/{itemId}", orderId, keyboardId).with(bearer()))
                .andExpect(status().isNoContent());
        assertTotal(getOrder(orderId), "0.00");

        long remainingItemId = createItem(orderId, "Mouse", 1, "10.00");
        mvc.perform(delete("/api/orders/{id}", orderId).with(bearer()))
                .andExpect(status().isNoContent());
        assertThat(orderRepository.findById(orderId)).isEmpty();
        assertThat(orderItemRepository.findById(remainingItemId)).isEmpty();
    }

    @Test
    void isolatesItemCrudByItsParentOrderInTheDatabase() throws Exception {
        long ownerId = createOrder("Alice");
        long anotherOrderId = createOrder("Bob");
        long itemId = createItem(ownerId, "Keyboard", 2, "19.90");

        mvc.perform(get("/api/orders/{orderId}/items/{itemId}", anotherOrderId, itemId).with(bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/orders/{orderId}/items/{itemId}", anotherOrderId, itemId).with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderItemRequest("Changed", 1, new BigDecimal("1.00")))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/orders/{orderId}/items/{itemId}", anotherOrderId, itemId).with(bearer()))
                .andExpect(status().isNotFound());

        JsonNode owner = getOrder(ownerId);
        assertThat(owner.get("items").size()).isEqualTo(1);
        assertThat(owner.get("items").get(0).get("productName").asText()).isEqualTo("Keyboard");
        assertTotal(owner, "39.80");
        assertTotal(getOrder(anotherOrderId), "0.00");
    }

    @Test
    void rollsBackAnItemCreationWhenTheTotalOverflows() throws Exception {
        long orderId = createOrder("Alice");
        createItem(orderId, "Expensive", 1, "99999999999999999.99");

        mvc.perform(post("/api/orders/{orderId}/items", orderId).with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderItemRequest("Extra", 1, new BigDecimal("0.01")))))
                .andExpect(status().isBadRequest());

        JsonNode order = getOrder(orderId);
        assertThat(order.get("items").size()).isEqualTo(1);
        assertTotal(order, "99999999999999999.99");
    }

    @Test
    void rollsBackAnItemUpdateWhenTheRecalculatedTotalOverflows() throws Exception {
        long orderId = createOrder("Alice");
        createItem(orderId, "Expensive", 1, "99999999999999999.98");
        long itemId = createItem(orderId, "Extra", 1, "0.01");

        mvc.perform(put("/api/orders/{orderId}/items/{itemId}", orderId, itemId).with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderItemRequest("Changed", 2, new BigDecimal("0.01")))))
                .andExpect(status().isBadRequest());

        JsonNode order = getOrder(orderId);
        assertTotal(order, "99999999999999999.99");
        var item = orderItemRepository.findById(itemId).orElseThrow();
        assertThat(item.getProductName()).isEqualTo("Extra");
        assertThat(item.getQuantity()).isEqualTo(1);
        assertThat(item.getSubtotal()).isEqualByComparingTo("0.01");
    }

    @Test
    void rejectsInvalidInputAndUnauthenticatedWritesWithoutPersistingOrders() throws Exception {
        long before = orderRepository.count();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Alice\",\"customerEmail\":\"alice@example.com\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders").with(bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"\",\"customerEmail\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerName").exists())
                .andExpect(jsonPath("$.errors.customerEmail").exists());
        assertThat(orderRepository.count()).isEqualTo(before);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsCommittedChangesWithoutCustomerOrProductData(CapturedOutput output) throws Exception {
        long orderId = createOrder("private-customer-name-marker");
        long itemId = createItem(orderId, "private-product-name-marker", 1, "10.00");
        mvc.perform(put("/api/orders/{id}", orderId).with(bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"private-customer-name-marker\",\"customerEmail\":\"customer@example.com\",\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/orders/{orderId}/items/{itemId}", orderId, itemId).with(bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/orders/{id}", orderId).with(bearer())).andExpect(status().isNoContent());
        assertThat(output.getAll()).contains("event=ORDER_CREATED orderId=" + orderId,
                        "event=ORDER_UPDATED orderId=" + orderId, "event=ORDER_DELETED orderId=" + orderId,
                        "event=ORDER_ITEM_CREATED", "event=ORDER_ITEM_DELETED")
                .doesNotContain("private-customer-name-marker", "private-product-name-marker", "customer@example.com",
                        JwtTestTokens.SECRET);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void doesNotLogAnUpdateAsSuccessfulWhenTheDatabaseCommitFails(CapturedOutput output) throws Exception {
        long orderId = createOrder("Alice");
        jdbcTemplate.execute("alter table orders add constraint test_reject_cancelled check (status <> 'CANCELLED')");
        try {
            mvc.perform(put("/api/orders/{id}", orderId).with(bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"customerName\":\"Alice\",\"customerEmail\":\"customer@example.com\",\"status\":\"CANCELLED\"}"))
                    .andExpect(status().isInternalServerError());
            assertThat(getOrder(orderId).get("status").asText()).isEqualTo("PENDING");
            assertThat(output.getAll()).contains("event=REQUEST_FAILED status=500")
                    .doesNotContain("event=ORDER_UPDATED orderId=" + orderId);
        } finally {
            jdbcTemplate.execute("alter table orders drop constraint test_reject_cancelled");
        }
    }

    @Test
    void paginatesPersistedOrders() throws Exception {
        createOrder("Alice");
        createOrder("Bob");
        createOrder("Carol");

        mvc.perform(get("/api/orders").with(bearer()).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    private long createOrder(String name) throws Exception {
        String response = mvc.perform(post("/api/orders").with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateOrderRequest(name, "customer@example.com"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(response).get("id").asLong();
        createdOrderIds.add(id);
        return id;
    }

    private long createItem(long orderId, String product, int quantity, String price) throws Exception {
        String response = mvc.perform(post("/api/orders/{orderId}/items", orderId).with(bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderItemRequest(product, quantity, new BigDecimal(price)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private JsonNode getOrder(long orderId) throws Exception {
        String response = mvc.perform(get("/api/orders/{id}", orderId).with(bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response);
    }

    private void assertTotal(JsonNode order, String expected) {
        assertThat(new BigDecimal(order.get("totalAmount").asText())).isEqualByComparingTo(expected);
    }
}
