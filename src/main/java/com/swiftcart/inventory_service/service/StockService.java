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
        List<Long> result = redisTemplate.execute(
                reserveStockScript,
                Collections.singletonList(stockKey),
                quantity.toString()
        );

        if (result == null || result.isEmpty()) {
            throw new RuntimeException("Redis script execution failed for productId: " + productId);
        }

        return result.get(0) == 1;
    }
}