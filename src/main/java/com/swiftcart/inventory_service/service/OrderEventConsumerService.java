package com.swiftcart.inventory_service.service;

import com.swiftcart.inventory_service.dto.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OrderEventConsumerService {
    private final InventoryService inventoryService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String dlqTopic;

    public OrderEventConsumerService(
            InventoryService inventoryService,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${inventory.dlq.topic:inventory-dlq}") String dlqTopic
    ) {
        this.inventoryService = inventoryService;
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic = dlqTopic;
    }

    @KafkaListener(
            topics = "${order.topic.name}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrderCreatedEvent(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment
    ) {
        log.info("Received ORDER_CREATED event: orderId={}, requestId={}, key={}, partition={}, offset={}",
                event.getOrderId(), event.getRequestId(), key, partition, offset);

        try {
            // Validate event
            if (event.getOrderId() == null || event.getRequestId() == null) {
                log.error("Invalid event received: missing orderId or requestId");
                acknowledgment.acknowledge();
                return;
            }

            // Process the event
            inventoryService.processOrderCreatedEvent(event);

            // Acknowledge successful processing
            acknowledgment.acknowledge();
            log.info("Successfully processed ORDER_CREATED event: orderId={}", event.getOrderId());

        } catch (RuntimeException e) {
            // Check if it's a duplicate event
            if (e.getMessage() != null && e.getMessage().contains("Duplicate")) {
                log.info("Acknowledging duplicate event: orderId={}, requestId={}",
                        event.getOrderId(), event.getRequestId());
                acknowledgment.acknowledge();
                return;
            }

            // For other runtime exceptions, send to DLQ
            log.error("Failed to process ORDER_CREATED event: orderId={}, error={}",
                    event.getOrderId(), e.getMessage(), e);
            sendToDlq(key, event);
            acknowledgment.acknowledge();

        } catch (Exception e) {
            // Unexpected errors
            log.error("Unexpected error processing ORDER_CREATED event: orderId={}",
                    event.getOrderId(), e);
            sendToDlq(key, event);
            acknowledgment.acknowledge();
        }
    }

    private void sendToDlq(String key, OrderCreatedEvent event) {
        try {
            kafkaTemplate.send(dlqTopic, key, event).get();
            log.info("Sent failed event to DLQ: orderId={}, key={}", event.getOrderId(), key);
        } catch (Exception dlqError) {
            log.error("Failed to send to DLQ: orderId={}, key={}", event.getOrderId(), key, dlqError);
        }
    }
}