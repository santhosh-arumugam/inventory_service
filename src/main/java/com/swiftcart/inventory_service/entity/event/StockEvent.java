package com.swiftcart.inventory_service.entity.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StockEvent {
    private String eventType;
    private String requestId;
    private Long orderId;
}
