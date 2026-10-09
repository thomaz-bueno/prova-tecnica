package com.example.orders.service;

import com.example.orders.dto.CreateOrderRequest;
import com.example.orders.dto.OrderItemRequest;
import com.example.orders.dto.UpdateOrderRequest;
import com.example.orders.entity.Order;
import com.example.orders.entity.OrderItem;
import com.example.orders.entity.OrderStatus;
import com.example.orders.exception.InvalidOrderAmountException;
import com.example.orders.exception.OrderItemNotFoundException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.mapper.OrderMapper;
import com.example.orders.repository.OrderItemRepository;
import com.example.orders.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, orderItemRepository, new OrderMapper());
    }

    @Test
    void createsAnEmptyPendingOrderWithServerGeneratedDefaults() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 1L);
            return order;
        });

        var response = service.createOrder(new CreateOrderRequest("Alice", "alice@example.com"));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.customerName()).isEqualTo("Alice");
        assertThat(response.customerEmail()).isEqualTo("alice@example.com");
        assertThat(response.orderDate()).isNotNull();
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.items()).isEmpty();
        assertThat(response.totalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void delegatesPaginationToTheRepositoryAndMapsSummaries() {
        Order order = order();
        PageRequest pageable = PageRequest.of(1, 2);
        when(orderRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 5));

        var page = service.getOrders(pageable);

        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).singleElement()
                .satisfies(response -> assertThat(response.id()).isEqualTo(1L));
        verify(orderRepository).findAll(pageable);
    }

    @Test
    void returnsOrderDetailsWithItemDtos() {
        Order order = order();
        attachItem(order, 7L, 2, "19.90");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        var response = service.getOrder(1L);

        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.subtotal()).isEqualByComparingTo("39.80"));
    }

    @Test
    void reportsMissingOrdersInsteadOfReturningNull() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder(99L))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.getItems(99L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void updatesCustomerAndStatusWithoutReplacingItemsOrCreationDate() {
        Order order = order();
        OrderItem item = attachItem(order, 7L, 2, "19.90");
        var originalDate = order.getOrderDate();
        order.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        var response = service.updateOrder(1L,
                new UpdateOrderRequest("Bob", "bob@example.com", OrderStatus.PENDING));

        assertThat(response.customerName()).isEqualTo("Bob");
        assertThat(response.customerEmail()).isEqualTo("bob@example.com");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.orderDate()).isEqualTo(originalDate);
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.items()).hasSize(1);
        assertThat(order.getItems()).containsExactly(item);
        assertThat(response.totalAmount()).isEqualByComparingTo("39.80");
    }

    @Test
    void deletesAnExistingOrder() {
        Order order = order();
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        service.deleteOrder(1L);

        verify(orderRepository).delete(order);
    }

    @Test
    void rejectsMutationsWhenTheOrderDoesNotExist() {
        when(orderRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateOrder(99L,
                new UpdateOrderRequest("Alice", "alice@example.com", OrderStatus.CONFIRMED)))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.deleteOrder(99L))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.createItem(99L, request(2, "19.90")))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void listsItemsWithoutExposingTheirOrderReference() {
        Order order = order();
        attachItem(order, 7L, 2, "19.90");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        var response = service.getItems(1L);

        assertThat(response).singleElement()
                .satisfies(item -> assertThat(item.id()).isEqualTo(7L));
    }

    @Test
    void looksUpAnItemWithinItsParentOrder() {
        Order order = order();
        OrderItem item = attachItem(order, 7L, 2, "19.90");
        when(orderItemRepository.findByIdAndOrderId(7L, 1L)).thenReturn(Optional.of(item));

        assertThat(service.getItem(1L, 7L).id()).isEqualTo(7L);
    }

    @Test
    void rejectsReadingUpdatingAndDeletingAnItemThroughAnotherOrder() {
        Order otherOrder = order();
        ReflectionTestUtils.setField(otherOrder, "id", 2L);
        when(orderRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(otherOrder));
        when(orderItemRepository.findByIdAndOrderId(7L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getItem(2L, 7L))
                .isInstanceOf(OrderItemNotFoundException.class);
        assertThatThrownBy(() -> service.updateItem(2L, 7L, request(1, "10.00")))
                .isInstanceOf(OrderItemNotFoundException.class);
        assertThatThrownBy(() -> service.deleteItem(2L, 7L))
                .isInstanceOf(OrderItemNotFoundException.class);
        assertThat(otherOrder.getItems()).isEmpty();
        assertThat(otherOrder.getTotalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void calculatesSubtotalAndIncludesExistingItemsInTheTotal() {
        Order order = order();
        attachItem(order, 7L, 1, "10.00");
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(invocation -> {
            OrderItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 8L);
            return item;
        });

        var response = service.createItem(1L, request(2, "19.90"));

        assertThat(response.id()).isEqualTo(8L);
        assertThat(response.subtotal()).isEqualByComparingTo("39.80");
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("49.80");
    }

    @Test
    void calculatesSmallDecimalPricesWithoutFloatingPointErrors() {
        Order order = order();
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createItem(1L, request(3, "0.1"));

        assertThat(response.unitPrice()).isEqualTo(new BigDecimal("0.10"));
        assertThat(response.subtotal()).isEqualTo(new BigDecimal("0.30"));
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("0.30"));
    }

    @Test
    void recalculatesTheWholeTotalAfterUpdatingAnItem() {
        Order order = order();
        OrderItem item = attachItem(order, 7L, 2, "19.90");
        OrderItem anotherItem = attachItem(order, 8L, 1, "10.00");
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByIdAndOrderId(7L, 1L)).thenReturn(Optional.of(item));

        var response = service.updateItem(1L, 7L, request(3, "0.10"));

        assertThat(response.quantity()).isEqualTo(3);
        assertThat(response.unitPrice()).isEqualTo(new BigDecimal("0.10"));
        assertThat(response.subtotal()).isEqualTo(new BigDecimal("0.30"));
        assertThat(anotherItem.getSubtotal()).isEqualByComparingTo("10.00");
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("10.30"));
    }

    @Test
    void removesAnItemAndRecalculatesTheRemainingTotal() {
        Order order = order();
        OrderItem item = attachItem(order, 7L, 2, "19.90");
        OrderItem remainingItem = attachItem(order, 8L, 1, "10.00");
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByIdAndOrderId(7L, 1L)).thenReturn(Optional.of(item));

        service.deleteItem(1L, 7L);

        assertThat(order.getItems()).containsExactly(remainingItem);
        assertThat(item.getOrder()).isNull();
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void resetsTheTotalToZeroWhenTheLastItemIsRemoved() {
        Order order = order();
        OrderItem item = attachItem(order, 7L, 2, "19.90");
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByIdAndOrderId(7L, 1L)).thenReturn(Optional.of(item));

        service.deleteItem(1L, 7L);

        assertThat(order.getItems()).isEmpty();
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void rejectsSubtotalOverflowBeforeAttachingOrSavingTheItem() {
        Order order = order();
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createItem(1L, request(2, "99999999999999999.99")))
                .isInstanceOf(InvalidOrderAmountException.class);
        assertThat(order.getItems()).isEmpty();
        verify(orderItemRepository, never()).save(any());
    }

    @Test
    void rejectsTotalOverflowEvenWhenIndividualSubtotalsFit() {
        Order order = order();
        attachItem(order, 7L, 1, "99999999999999999.99");
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createItem(1L, request(1, "0.01")))
                .isInstanceOf(InvalidOrderAmountException.class);
        verify(orderItemRepository, never()).save(any());
    }

    private Order order() {
        Order order = new Order("Alice", "alice@example.com");
        ReflectionTestUtils.setField(order, "id", 1L);
        return order;
    }

    private OrderItem attachItem(Order order, Long id, int quantity, String price) {
        OrderItem item = new OrderItem("Keyboard", quantity, new BigDecimal(price));
        ReflectionTestUtils.setField(item, "id", id);
        item.setSubtotal(new BigDecimal(price).multiply(BigDecimal.valueOf(quantity)));
        order.addItem(item);
        order.setTotalAmount(order.getItems().stream().map(OrderItem::getSubtotal)
                .reduce(new BigDecimal("0.00"), BigDecimal::add));
        return item;
    }

    private OrderItemRequest request(int quantity, String price) {
        return new OrderItemRequest("Keyboard", quantity, new BigDecimal(price));
    }
}
