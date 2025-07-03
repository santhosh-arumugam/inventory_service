package com.swiftcart.inventory_service.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class StockService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List> reserveStockScript;

    public StockService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.reserveStockScript = new DefaultRedisScript<>();
        this.reserveStockScript.setLocation(new ClassPathResource("scripts/reserve_stock.lua"));
        this.reserveStockScript.setResultType(List.class);
    }

    public boolean reserveStock(Long productId, Integer quantity) {
        if (productId == null || quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Invalid productId or quantity");
        }
        String stockKey = "stock:productId:" + productId;
        try {
            List<Long> result = redisTemplate.execute(reserveStockScript,
                    Collections.singletonList(stockKey), quantity.toString());
            if (result == null || result.isEmpty()) {
                throw new RuntimeException("Redis script execution failed for productId: " + productId);
            }
            return result.get(0) == 1;
        } catch (Exception e) {
            throw new RuntimeException("Failed to reserve stock for productId: " + productId, e);
        }
    }

    public void initializeStock(Long productId, Integer quantity) {
        if (productId == null || quantity == null || quantity < 0) {
            throw new IllegalArgumentException("Invalid productId or quantity");
        }
        String stockKey = "stock:productId:" + productId;
        redisTemplate.opsForHash().put(stockKey, "quantity", quantity.toString());
    }
}