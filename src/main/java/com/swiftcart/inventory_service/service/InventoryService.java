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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class InventoryService {
    private final OrderEventLogRepository orderEventLogRepository;
    private final InventoryRepository inventoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StockService stockService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public InventoryService(
            OrderEventLogRepository orderEventLogRepository,
            InventoryRepository inventoryRepository,
            OutboxEventRepository outboxEventRepository,
            StockService stockService,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.orderEventLogRepository = orderEventLogRepository;
        this.inventoryRepository = inventoryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.stockService = stockService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private boolean checkForDuplicateRequest(String requestId) {
        try {
            String idempotencyKey = "idempotency:request:" + requestId;
            Boolean isNewRequest = redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "PROCESSED");
            if (Boolean.TRUE.equals(isNewRequest)) {
                redisTemplate.expire(idempotencyKey, 24, TimeUnit.HOURS);
                return true;
            }
            log.warn("Duplicate request detected in Redis: requestId={}", requestId);
            return false;
        } catch (Exception e) {
            log.error("Redis connection error during duplicate check for requestId: {}", requestId, e);
            // If Redis is down, check database for duplicate
            return !orderEventLogRepository.existsById(requestId);
        }
    }

    private Optional<Inventory> getInventory(Long productId) {
        try {
            String cacheKey = "cache:productId:" + productId;
            Integer cachedStock = (Integer) redisTemplate.opsForValue().get(cacheKey);
            if (cachedStock != null) {
                Inventory inventory = new Inventory();
                inventory.setProductId(productId);
                inventory.setQuantity(cachedStock);
                return Optional.of(inventory);
            }
        } catch (Exception e) {
            log.warn("Redis cache miss for productId: {}, falling back to database", productId, e);
        }

        // Fallback to database
        Optional<Inventory> inventoryOpt = inventoryRepository.findById(productId);
        if (inventoryOpt.isPresent()) {
            try {
                Inventory inventory = inventoryOpt.get();
                String cacheKey = "cache:productId:" + productId;
                redisTemplate.opsForValue().set(cacheKey, inventory.getQuantity(), 1, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("Failed to cache inventory for productId: {}", productId, e);
            }
        }
        return inventoryOpt;
    }

    @Transactional("transactionManager")
    public void processOrderCreatedEvent(OrderCreatedEvent event) {
        String requestId = event.getRequestId();
        Long orderId = event.getOrderId();

        // Check idempotency
        if (!checkForDuplicateRequest(requestId)) {
            log.warn("Duplicate ORDER_CREATED event skipped: requestId={}", requestId);
            return; // Skip processing without throwing exception
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

            // Get inventory
            Optional<Inventory> inventoryOpt = getInventory(productId);
            if (inventoryOpt.isEmpty()) {
                allStockAvailable = false;
                failureReason = "Product not found: productId=" + productId;
                break;
            }

            // Try to reserve stock in Redis, fallback to in-memory check if Redis is down
            boolean reserved = false;
            try {
                reserved = stockService.reserveStock(productId, requestedQty);
            } catch (Exception e) {
                log.warn("Redis stock reservation failed for productId: {}, falling back to database check", productId, e);
                // Fallback: check stock in database and reserve
                Inventory inventory = inventoryOpt.get();
                if (inventory.getQuantity() >= requestedQty) {
                    inventory.setQuantity(inventory.getQuantity() - requestedQty);
                    inventoryRepository.save(inventory);
                    reserved = true;
                } else {
                    reserved = false;
                }
            }

            if (reserved) {
                // Update Postgres if not already done in fallback
                try {
                    Inventory inventory = inventoryOpt.get();
                    if (inventory.getQuantity() >= requestedQty) {
                        inventory.setQuantity(inventory.getQuantity() - requestedQty);
                        inventoryRepository.save(inventory);
                    }
                } catch (Exception e) {
                    log.warn("Database update failed for productId: {}", productId, e);
                }

                // Invalidate cache
                try {
                    redisTemplate.delete("cache:productId:" + productId);
                } catch (Exception e) {
                    log.warn("Cache invalidation failed for productId: {}", productId, e);
                }

                // Add to inventory event
                inventoryEvent.getOrderItems().add(new InventoryEvent.OrderItem(productId, requestedQty));
            } else {
                allStockAvailable = false;
                failureReason = "Insufficient stock for productId: " + productId;
                break;
            }
        }

        // Prepare outbox event
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateType("Inventory");
        outboxEvent.setAggregateId(orderId);
        outboxEvent.setCreatedAt(OffsetDateTime.now());
        outboxEvent.setPublished(false);

        if (allStockAvailable) {
            inventoryEvent.setEventType("STOCK_RESERVED");
            inventoryEvent.setStatus("SUCCESS");
            // Save event log only on success
            OrderEventLog orderEventLog = new OrderEventLog();
            orderEventLog.setRequestId(requestId);
            orderEventLog.setOrderId(orderId);
            orderEventLog.setEventType("ORDER_CREATED");
            orderEventLogRepository.save(orderEventLog);
        } else {
            inventoryEvent.setEventType("ORDER_CANCELLED");
            inventoryEvent.setStatus("FAILED");
            inventoryEvent.setReason(failureReason);
            inventoryEvent.setOrderItems(new ArrayList<>()); // Clear orderItems for failure
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

        if (!allStockAvailable) {
            log.warn("Stock reservation failed for orderId={}: {}", orderId, failureReason);
            throw new RuntimeException(failureReason);
        }
    }
}