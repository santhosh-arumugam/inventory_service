package com.swiftcart.inventory_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcart.inventory_service.entity.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OrderEventConsumerService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public OrderEventConsumerService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "orders-events", groupId = "inventory-service")
    public void handleOrderCreated(String message, @Header(KafkaHeaders.RECEIVED_KEY) String key, Acknowledgment acknowledgment) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            String idempotencyKey = "order:"+ event.getRequestId();
            if (Boolean.TRUE.equals(redisTemplate.hasKey(idempotencyKey))) {
                log.info("Duplicate ORDER_CREATED event for requestId: {}", event.getRequestId());
                acknowledgment.acknowledge();
                return;
            }
            log.info("Processing ORDER_CREATED event: requestId={}, orderId={}, items={}", event.getRequestId(), event.getOrderId(), event.getItems());
            redisTemplate.opsForValue().set(idempotencyKey, "processed", 1, TimeUnit.HOURS);
            acknowledgment.acknowledge();
        } catch (Exception exp){
            log.error("Error processing ORDER_CREATED: {}", message, exp);
        }
    }







}
