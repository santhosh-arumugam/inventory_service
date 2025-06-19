package com.swiftcart.inventory_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcart.inventory_service.entity.OrderCreatedEvent;
import com.swiftcart.inventory_service.entity.OrderItem;
import com.swiftcart.inventory_service.entity.event.StockEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
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
    private final StockService stockService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderEventConsumerService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper, StockService stockService, KafkaTemplate<String, String> kafkaTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.stockService = stockService;
        this.kafkaTemplate = kafkaTemplate;
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

            boolean allReserved = true;
            for (OrderItem item : event.getItems()) {
                if (!stockService.reserveStock(item.getProductId(), item.getQuantity())) {
                    allReserved = false;
                    break;
                }
            }

            StockEvent stockEvent = new StockEvent();
            stockEvent.setEventType(allReserved ? "STOCK_RESERVED" : "STOCK_FAILED");
            stockEvent.setRequestId(event.getRequestId());
            stockEvent.setOrderId(event.getOrderId());
            String eventJson = objectMapper.writeValueAsString(stockEvent);
            kafkaTemplate.send("inventory-events", stockEvent.getRequestId(), eventJson);

            log.info("Processing ORDER_CREATED event: requestId={}, orderId={}, items={}", event.getRequestId(), event.getOrderId(), event.getItems());
            redisTemplate.opsForValue().set(idempotencyKey, "processed", 1, TimeUnit.HOURS);

            acknowledgment.acknowledge();
        } catch (Exception exp){
            log.error("Error processing ORDER_CREATED: {}", message, exp);
        }
    }
}
