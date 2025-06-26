package com.swiftcart.inventory_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcart.inventory_service.dto.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderEventConsumerService {

    private final ObjectMapper objectMapper;
    private final InventoryService inventoryService;

    public OrderEventConsumerService(ObjectMapper objectMapper, InventoryService inventoryService) {
        this.objectMapper = objectMapper;
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = "order-events", groupId = "inventory-service-consumer-group")
    public void consumeOrderCreatedEvent(String message, Acknowledgment acknowledgment) throws JsonProcessingException {
        try {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            log.info("Received ORDER_CREATED event: orderId={}, requestId={}", event.getOrderId(), event.getRequestId());
            inventoryService.processOrderCreatedEvent(event);
            acknowledgment.acknowledge();
        }
        catch (Exception exp) {
            log.error("Failed to process ORDER_CREATED event: {}",message, exp);
            throw new RuntimeException("Failed to process ORDER_CREATED event", exp);
        }
    }
}
