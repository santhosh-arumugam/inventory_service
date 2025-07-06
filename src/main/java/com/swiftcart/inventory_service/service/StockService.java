package com.swiftcart.inventory_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class StockService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List> reserveStockScript;

    public boolean reserveStock(Long productId, Integer quantity) {
        if (productId == null || quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Invalid productId or quantity");
        }

        String stockKey = "stock:productId:" + productId;

        try {
            List<Long> result = redisTemplate.execute(
                    reserveStockScript,
                    Collections.singletonList(stockKey),
                    quantity.toString()
            );

            if (result == null || result.isEmpty()) {
                log.error("Redis script returned null/empty result for productId: {}", productId);
                return false;
            }

            Long resultCode = result.get(0);
            if (resultCode == 1) {
                // Success
                Long newQuantity = result.size() > 1 ? result.get(1) : null;
                log.debug("Stock reserved successfully for productId: {}, new quantity: {}", productId, newQuantity);
                return true;
            } else if (resultCode == -1) {
                // Product not found in Redis
                log.warn("Product not found in Redis: productId={}", productId);
                return false;
            } else {
                // Insufficient stock
                Long currentStock = result.size() > 1 ? result.get(1) : 0L;
                log.warn("Insufficient stock for productId: {}, requested: {}, available: {}", productId, quantity, currentStock);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to execute Redis stock reservation script for productId: {}", productId, e);
            throw new RuntimeException("Redis stock reservation failed", e);
        }
    }

    public void updateStock(Long productId, Integer quantity) {
        if (productId == null || quantity == null || quantity < 0) {
            throw new IllegalArgumentException("Invalid productId or quantity");
        }

        String stockKey = "stock:productId:" + productId;
        try {
            redisTemplate.opsForHash().put(stockKey, "quantity", String.valueOf(quantity));
            log.debug("Updated stock in Redis for productId: {} to quantity: {}", productId, quantity);
        } catch (Exception e) {
            log.error("Failed to update stock in Redis for productId: {}", productId, e);
            throw new RuntimeException("Failed to update Redis stock", e);
        }
    }
}