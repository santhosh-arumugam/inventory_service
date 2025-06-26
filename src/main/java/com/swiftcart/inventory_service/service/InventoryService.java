package com.swiftcart.inventory_service.service;

import com.swiftcart.inventory_service.dto.InventoryEvent;
import com.swiftcart.inventory_service.dto.OrderCreatedEvent;
import com.swiftcart.inventory_service.entity.Inventory;
import com.swiftcart.inventory_service.entity.OrderEventLog;
import com.swiftcart.inventory_service.repository.InventoryRepository;
import com.swiftcart.inventory_service.repository.OrderEventLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Optional;

@Service
@Slf4j
public class InventoryService {

    private final OrderEventLogRepository orderEventLogRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryEventProducerService eventProducerService;

    public InventoryService(
            OrderEventLogRepository orderEventLogRepository,
            InventoryRepository inventoryRepository,
            InventoryEventProducerService eventProducerService
    ) {
        this.orderEventLogRepository = orderEventLogRepository;
        this.inventoryRepository = inventoryRepository;
        this.eventProducerService = eventProducerService;
    }

    /**
     * Checks if the requestId is a duplicate in Postgres.
     * @param requestId The unique identifier for the event
     * @throws RuntimeException if the request is a duplicate
     */
    private void checkForDuplicateRequest(String requestId) {
        if (orderEventLogRepository.existsById(requestId)) {
            log.warn("Duplicate request detected in database: requestId={}", requestId);
            throw new RuntimeException(String.format("Duplicate ORDER_CREATED event: requestId=%s", requestId));
        }
    }

    @Transactional
    public void processOrderCreatedEvent(OrderCreatedEvent event) throws Exception {
        String requestId = event.getRequestId();
        Long orderId = event.getOrderId();

        // Check for duplicate request
        checkForDuplicateRequest(requestId);

        // Initialize inventory event
        InventoryEvent inventoryEvent = new InventoryEvent();
        inventoryEvent.setRequestId(requestId);
        inventoryEvent.setOrderId(orderId);
        inventoryEvent.setOrderItems(new ArrayList<>());

        boolean allStockAvailable = true;
        String failureReason = null;

        // Process each order item
        for (OrderCreatedEvent.OrderItem orderItem : event.getOrderItems()) {
            Long productId = orderItem.getProductId();
            Integer requestedQuantity = orderItem.getQuantity();

            // Lock inventory row to prevent race conditions
            Optional<Inventory> inventoryOpt = inventoryRepository.findByIdForUpdate(productId);
            if (inventoryOpt.isEmpty()) {
                allStockAvailable = false;
                failureReason = "Product not found: " + productId;
                break;
            }

            Inventory inventory = inventoryOpt.get();
            int currentStock = inventory.getQuantity();

            // Check stock availability
            if (currentStock >= requestedQuantity) {
                // Reserve stock
                inventory.setQuantity(currentStock - requestedQuantity);
                inventoryRepository.save(inventory);

                // Add to inventory event
                inventoryEvent.getOrderItems().add(
                        new InventoryEvent.OrderItem(productId, requestedQuantity)
                );
            } else {
                allStockAvailable = false;
                failureReason = "Insufficient stock for product: " + productId + ", available: " + currentStock;
                break;
            }
        }

        // Save event log
        OrderEventLog orderEventLog = new OrderEventLog();
        orderEventLog.setRequestId(requestId);
        orderEventLog.setOrderId(orderId);
        orderEventLog.setEventType("ORDER_CREATED");
        orderEventLogRepository.save(orderEventLog);

        // Publish inventory event
        if (allStockAvailable) {
            inventoryEvent.setEventType("STOCK_RESERVED");
            inventoryEvent.setStatus("SUCCESS");
        } else {
            inventoryEvent.setEventType("STOCK_FAILED");
            inventoryEvent.setStatus("FAILED");
            inventoryEvent.setReason(failureReason);
        }
        eventProducerService.publishInventoryEvent(inventoryEvent);

        if (!allStockAvailable) {
            throw new RuntimeException(failureReason);
        }
    }
}