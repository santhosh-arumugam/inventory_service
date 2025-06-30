package com.swiftcart.inventory_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcart.inventory_service.dto.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class OrderEventConsumerService {
    private final ObjectMapper objectMapper;
    private final InventoryService inventoryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String dlqTopic;

    public OrderEventConsumerService(
            ObjectMapper objectMapper,
            InventoryService inventoryService,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${inventory.dlq.topic:inventory-dlq}") String dlqTopic
    ) {
        this.objectMapper = objectMapper;
        this.inventoryService = inventoryService;
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic = dlqTopic;
    }

    @KafkaListener(topics = "${order.topic.name:orders-events}", groupId = "inventory-service-consumer-group")
    public void consumeOrderCreatedEvent(
            String message,
            Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            log.info("Received ORDER_CREATED event: orderId={}, requestId={}", event.getOrderId(), event.getRequestId());
            inventoryService.processOrderCreatedEvent(event);
            acknowledgment.acknowledge();
        } catch (RuntimeException e) {
            if (e.getMessage().contains("Duplicate ORDER_CREATED event")) {
                log.info("Acknowledging duplicate event: key={}", key);
                acknowledgment.acknowledge();
                return;
            }
            log.error("Failed to process ORDER_CREATED event: {}", message, e);
            sendToDlq(key, message);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Unexpected error processing ORDER_CREATED event: {}", message, e);
            sendToDlq(key, message);
            acknowledgment.acknowledge();
        }
    }

    @Transactional("kafkaTransactionManager")
    private void sendToDlq(String key, String message) {
        try {
            kafkaTemplate.send(dlqTopic, key, message);
            log.info("Sent to DLQ: key={}", key);
        } catch (Exception dlqError) {
            log.error("Failed to send to DLQ: key={}, message={}", key, message, dlqError);
        }
    }

    @KafkaListener(topics = "${inventory.dlq.topic:inventory-dlq}", groupId = "inventory-dlq-group")
    public void handleDlq(
            String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        log.error("Received in DLQ: key={}, message={}", key, message);
        // Acknowledge DLQ message to prevent reprocessing
    }
}