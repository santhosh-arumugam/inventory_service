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

            // Check and reserve stock with proper Redis-DB sync
            StockReservationResult result = reserveStockWithSync(productId, requestedQty);

            if (result.isSuccess()) {
                inventoryEvent.getOrderItems().add(new InventoryEvent.OrderItem(productId, requestedQty));
            } else {
                allStockAvailable = false;
                failureReason = result.getReason();
                break;
            }
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

    private StockReservationResult reserveStockWithSync(Long productId, Integer requestedQty) {
        // First, check if product exists in database
        Optional<Inventory> inventoryOpt = inventoryRepository.findById(productId);
        if (inventoryOpt.isEmpty()) {
            return new StockReservationResult(false, "Product not found in database: productId=" + productId);
        }

        Inventory inventory = inventoryOpt.get();

        // Sync Redis with DB if needed
        syncRedisWithDatabase(productId, inventory.getQuantity());

        // Try to reserve stock in Redis
        try {
            boolean reserved = stockService.reserveStock(productId, requestedQty);

            if (reserved) {
                // Update database to match Redis
                inventory.setQuantity(inventory.getQuantity() - requestedQty);
                inventoryRepository.save(inventory);
                log.info("Reserved {} units of productId={}, remaining: {}", requestedQty, productId, inventory.getQuantity());
                return new StockReservationResult(true, null);
            } else {
                return new StockReservationResult(false, "Insufficient stock for productId: " + productId);
            }
        } catch (Exception e) {
            log.error("Error reserving stock in Redis for productId: {}", productId, e);

            // Fallback: Try direct database reservation
            if (inventory.getQuantity() >= requestedQty) {
                inventory.setQuantity(inventory.getQuantity() - requestedQty);
                inventoryRepository.save(inventory);

                // Try to sync Redis after DB update
                try {
                    String stockKey = "stock:productId:" + productId;
                    redisTemplate.opsForHash().put(stockKey, "quantity", String.valueOf(inventory.getQuantity()));
                } catch (Exception redisEx) {
                    log.warn("Failed to update Redis after DB reservation for productId: {}", productId);
                }

                log.info("Reserved {} units of productId={} via database, remaining: {}", requestedQty, productId, inventory.getQuantity());
                return new StockReservationResult(true, null);
            } else {
                return new StockReservationResult(false, "Insufficient stock for productId: " + productId);
            }
        }
    }

    private void syncRedisWithDatabase(Long productId, Integer dbQuantity) {
        try {
            String stockKey = "stock:productId:" + productId;
            Object redisQuantity = redisTemplate.opsForHash().get(stockKey, "quantity");

            if (redisQuantity == null) {
                // Redis doesn't have this product, sync from DB
                redisTemplate.opsForHash().put(stockKey, "quantity", String.valueOf(dbQuantity));
                log.info("Synced Redis with DB for productId={}, quantity={}", productId, dbQuantity);
            }
        } catch (Exception e) {
            log.warn("Failed to sync Redis with database for productId: {}", productId, e);
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

    private static class StockReservationResult {
        private final boolean success;
        private final String reason;

        public StockReservationResult(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getReason() {
            return reason;
        }
    }
}