package com.example.orders.controller;

import com.example.orders.dto.OrderItemRequest;
import com.example.orders.dto.OrderItemResponse;
import com.example.orders.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/orders/{orderId}/items")
public class OrderItemController {

    private final OrderService orderService;

    public OrderItemController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderItemResponse> getItems(@PathVariable @Positive Long orderId) {
        return orderService.getItems(orderId);
    }

    @GetMapping("/{itemId}")
    public OrderItemResponse getItem(@PathVariable @Positive Long orderId,
                                    @PathVariable @Positive Long itemId) {
        return orderService.getItem(orderId, itemId);
    }

    @PostMapping
    public ResponseEntity<OrderItemResponse> createItem(
            @PathVariable @Positive Long orderId,
            @Valid @RequestBody OrderItemRequest request) {
        OrderItemResponse response = orderService.createItem(orderId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{itemId}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{itemId}")
    public OrderItemResponse updateItem(
            @PathVariable @Positive Long orderId,
            @PathVariable @Positive Long itemId,
            @Valid @RequestBody OrderItemRequest request) {
        return orderService.updateItem(orderId, itemId, request);
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable @Positive Long orderId,
                                          @PathVariable @Positive Long itemId) {
        orderService.deleteItem(orderId, itemId);
        return ResponseEntity.noContent().build();
    }
}
