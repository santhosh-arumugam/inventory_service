package com.swiftcart.inventory_service.service;

import com.swiftcart.inventory_service.dto.InventoryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class InventoryEventProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public InventoryEventProducerService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${inventory.topic.name:inventory-events}")
    private String topicName;

    public void publishInventoryEvent(InventoryEvent event) throws Exception {
        String eventJson = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(topicName, event.getRequestId(), eventJson);
        log.info("Published InventoryEvent: requestId={}, eventType={}", event.getRequestId(), event.getEventType());
    }
}