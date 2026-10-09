package com.example.orders.service;

import com.example.orders.dto.CreateOrderRequest;
import com.example.orders.dto.OrderItemRequest;
import com.example.orders.dto.OrderItemResponse;
import com.example.orders.dto.OrderResponse;
import com.example.orders.dto.OrderSummaryResponse;
import com.example.orders.dto.UpdateOrderRequest;
import com.example.orders.entity.Order;
import com.example.orders.entity.OrderItem;
import com.example.orders.exception.InvalidOrderAmountException;
import com.example.orders.exception.OrderItemNotFoundException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.mapper.OrderMapper;
import com.example.orders.repository.OrderItemRepository;
import com.example.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper;

    public OrderService(OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderMapper = orderMapper;
    }

    public Page<OrderSummaryResponse> getOrders(Pageable pageable) {
        return orderRepository.findAll(pageable).map(orderMapper::toSummaryResponse);
    }

    public OrderResponse getOrder(Long id) {
        return orderMapper.toResponse(findOrder(id));
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        Order order = new Order(request.customerName(), request.customerEmail());
        OrderResponse response = orderMapper.toResponse(orderRepository.save(order));
        logAfterCommit("ORDER_CREATED", response.id(), null);
        return response;
    }

    @Transactional
    public OrderResponse updateOrder(Long id, UpdateOrderRequest request) {
        Order order = findOrderForUpdate(id);
        order.setCustomerName(request.customerName());
        order.setCustomerEmail(request.customerEmail());
        order.setStatus(request.status());
        OrderResponse response = orderMapper.toResponse(order);
        logAfterCommit("ORDER_UPDATED", id, null);
        return response;
    }

    @Transactional
    public void deleteOrder(Long id) {
        orderRepository.delete(findOrderForUpdate(id));
        logAfterCommit("ORDER_DELETED", id, null);
    }

    public List<OrderItemResponse> getItems(Long orderId) {
        return findOrder(orderId).getItems().stream()
                .map(orderMapper::toItemResponse).toList();
    }

    public OrderItemResponse getItem(Long orderId, Long itemId) {
        return orderMapper.toItemResponse(findItem(orderId, itemId));
    }

    @Transactional
    public OrderItemResponse createItem(Long orderId, OrderItemRequest request) {
        Order order = findOrderForUpdate(orderId);
        BigDecimal unitPrice = normalizePrice(request.unitPrice());
        BigDecimal subtotal = calculateSubtotal(unitPrice, request.quantity());
        OrderItem item = new OrderItem(request.productName(), request.quantity(), unitPrice);
        item.setSubtotal(subtotal);
        order.addItem(item);
        recalculateTotal(order);
        OrderItemResponse response = orderMapper.toItemResponse(orderItemRepository.save(item));
        logAfterCommit("ORDER_ITEM_CREATED", orderId, response.id());
        return response;
    }

    @Transactional
    public OrderItemResponse updateItem(Long orderId, Long itemId, OrderItemRequest request) {
        Order order = findOrderForUpdate(orderId);
        OrderItem item = findItem(orderId, itemId);
        BigDecimal unitPrice = normalizePrice(request.unitPrice());
        BigDecimal subtotal = calculateSubtotal(unitPrice, request.quantity());
        item.setProductName(request.productName());
        item.setQuantity(request.quantity());
        item.setUnitPrice(unitPrice);
        item.setSubtotal(subtotal);
        recalculateTotal(order);
        OrderItemResponse response = orderMapper.toItemResponse(item);
        logAfterCommit("ORDER_ITEM_UPDATED", orderId, itemId);
        return response;
    }

    @Transactional
    public void deleteItem(Long orderId, Long itemId) {
        Order order = findOrderForUpdate(orderId);
        OrderItem item = findItem(orderId, itemId);
        order.removeItem(item);
        recalculateTotal(order);
        logAfterCommit("ORDER_ITEM_DELETED", orderId, itemId);
    }

    private void logAfterCommit(String event, Long orderId, Long itemId) {
        Runnable message = () -> log.info("event={} orderId={} itemId={}", event, orderId, itemId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            message.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                message.run();
            }
        });
    }

    private Order findOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private Order findOrderForUpdate(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private OrderItem findItem(Long orderId, Long itemId) {
        return orderItemRepository.findByIdAndOrderId(itemId, orderId)
                .orElseThrow(() -> new OrderItemNotFoundException(orderId, itemId));
    }

    private BigDecimal normalizePrice(BigDecimal unitPrice) {
        return unitPrice.setScale(2, RoundingMode.UNNECESSARY);
    }

    private BigDecimal calculateSubtotal(BigDecimal unitPrice, Integer quantity) {
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        validateAmount(subtotal);
        return subtotal;
    }

    private void recalculateTotal(Order order) {
        BigDecimal total = order.getItems().stream()
                .map(OrderItem::getSubtotal)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        validateAmount(total);
        order.setTotalAmount(total);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidOrderAmountException();
        }
    }
}
