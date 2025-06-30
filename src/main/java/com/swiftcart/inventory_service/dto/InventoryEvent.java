package com.swiftcart.inventory_service.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InventoryEvent {
    private int version = 1;
    private String eventType;
    private String requestId;
    private Long orderId;
    private List<OrderItem> orderItems;
    private OffsetDateTime ts;
    private String status;
    private String reason;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItem {
        private Long productId;
        private Integer quantity;
    }
}
