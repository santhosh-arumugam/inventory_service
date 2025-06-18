package com.swiftcart.inventory_service.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class StockService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> reserveStockScript;

    public StockService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.reserveStockScript = new DefaultRedisScript<>();
        this.reserveStockScript.setLocation(new ClassPathResource("scripts/reserve_stock.lua"));
        this.reserveStockScript.setResultType(Long.class);
    }

    public boolean reserveStock(Long productId, Integer quantity) {
        String stockKey = "stock:"+productId;
        Long result = redisTemplate.execute(reserveStockScript, Collections.singletonList(stockKey), quantity);
        return result != null && result == 1;
    }
}
