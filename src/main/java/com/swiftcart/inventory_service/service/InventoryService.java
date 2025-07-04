package com.swiftcart.inventory_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcart.inventory_service.dto.InventoryEvent;
import com.swiftcart.inventory_service.dto.OrderCreatedEvent;
import com.swiftcart.inventory_service.entity.Inventory;
import com.swiftcart.inventory_service.entity.OrderEventLog;
import com.swiftcart.inventory_service.entity.OutboxEvent;
import com.swiftcart.inventory_service.repository.InventoryRepository;
import com.swiftcart.inventory_service.repository.OrderEventLogRepository;
import com.swiftcart.inventory_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryService {
    private final OrderEventLogRepository orderEventLogRepository;
    private final InventoryRepository inventoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StockService stockService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void processOrderCreatedEvent(OrderCreatedEvent event) {
        String requestId = event.getRequestId();
        Long orderId = event.getOrderId();

        // Check idempotency
        if (!checkForDuplicateRequest(requestId)) {
            log.warn("Duplicate ORDER_CREATED event skipped: requestId={}", requestId);
            return;
        }

        // Initialize inventory event
        InventoryEvent inventoryEvent = new InventoryEvent();
        inventoryEvent.setVersion(1);
        inventoryEvent.setRequestId(requestId);
        inventoryEvent.setOrderId(orderId);
        inventoryEvent.setOrderItems(new ArrayList<>());
        inventoryEvent.setTs(OffsetDateTime.now());

        boolean allStockAvailable = true;
        String failureReason = null;

        // Process each order item
        for (OrderCreatedEvent.OrderItem orderItem : event.getOrderItems()) {
            Long productId = orderItem.getProductId();
            Integer requestedQty = orderItem.getQuantity();

            // Check and reserve stock
            if (!reserveStock(productId, requestedQty)) {
                allStockAvailable = false;
                failureReason = "Insufficient stock for productId: " + productId;
                break;
            }

            inventoryEvent.getOrderItems().add(new InventoryEvent.OrderItem(productId, requestedQty));
        }

        // Prepare outbox event
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType("Inventory");
        outboxEvent.setAggregateId(orderId);
        outboxEvent.setCreatedAt(OffsetDateTime.now());
        outboxEvent.setPublished(false);

        if (allStockAvailable) {
            inventoryEvent.setEventType("STOCK_RESERVED");
            inventoryEvent.setStatus("SUCCESS");

            // Save event log
            OrderEventLog orderEventLog = new OrderEventLog();
            orderEventLog.setRequestId(requestId);
            orderEventLog.setOrderId(orderId);
            orderEventLog.setEventType("ORDER_CREATED");
            orderEventLogRepository.save(orderEventLog);
        } else {
            inventoryEvent.setEventType("ORDER_CANCELLED");
            inventoryEvent.setStatus("FAILED");
            inventoryEvent.setReason(failureReason);
            inventoryEvent.setOrderItems(new ArrayList<>());
        }

        // Save outbox event
        try {
            outboxEvent.setEventType(inventoryEvent.getEventType());
            outboxEvent.setPayload(objectMapper.writeValueAsString(inventoryEvent));
            outboxEventRepository.save(outboxEvent);
            log.info("Saved {} event to outbox for orderId={}", inventoryEvent.getEventType(), orderId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {} event for orderId={}", inventoryEvent.getEventType(), orderId, e);
            throw new RuntimeException("Failed to save outbox event", e);
        }
    }

    private boolean checkForDuplicateRequest(String requestId) {
        try {
            String idempotencyKey = "idempotency:request:" + requestId;
            Boolean isNewRequest = redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "PROCESSED");
            if (Boolean.TRUE.equals(isNewRequest)) {
                redisTemplate.expire(idempotencyKey, 24, TimeUnit.HOURS);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Redis error during duplicate check for requestId: {}", requestId, e);
            // Fallback to database
            return !orderEventLogRepository.existsById(requestId);
        }
    }

    private boolean reserveStock(Long productId, Integer requestedQty) {
        try {
            // Try Redis first
            return stockService.reserveStock(productId, requestedQty);
        } catch (Exception e) {
            log.warn("Redis stock reservation failed for productId: {}, falling back to database", productId, e);

            // Fallback to database
            Optional<Inventory> inventoryOpt = inventoryRepository.findById(productId);
            if (inventoryOpt.isPresent()) {
                Inventory inventory = inventoryOpt.get();
                if (inventory.getQuantity() >= requestedQty) {
                    inventory.setQuantity(inventory.getQuantity() - requestedQty);
                    inventoryRepository.save(inventory);

                    // Try to update Redis cache
                    try {
                        redisTemplate.delete("cache:productId:" + productId);
                    } catch (Exception redisEx) {
                        log.warn("Failed to invalidate cache for productId: {}", productId);
                    }
                    return true;
                }
            }
            return false;
        }
    }
}