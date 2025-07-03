package com.swiftcart.inventory_service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
@Slf4j
public class HealthCheckController {

    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @GetMapping("/redis")
    public Map<String, Object> checkRedis() {
        Map<String, Object> status = new HashMap<>();

        try {
            // Test Redis connection
            String testKey = "health:check:" + System.currentTimeMillis();
            String testValue = "OK";

            // Set with expiration
            redisTemplate.opsForValue().set(testKey, testValue, 5, TimeUnit.SECONDS);

            // Get value back
            String retrievedValue = (String) redisTemplate.opsForValue().get(testKey);

            // Delete test key
            redisTemplate.delete(testKey);

            if (testValue.equals(retrievedValue)) {
                status.put("status", "UP");
                status.put("message", "Redis is connected and working");
                status.put("test", "Read/Write successful");
            } else {
                status.put("status", "DOWN");
                status.put("message", "Redis connected but read/write failed");
            }

        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("message", "Redis connection failed");
            status.put("error", e.getMessage());
            status.put("troubleshooting", Map.of(
                    "check1", "Verify Redis is running: sudo systemctl status redis",
                    "check2", "Verify Redis bind address: grep ^bind /etc/redis/redis.conf",
                    "check3", "Test connection: redis-cli -h 192.168.25.187 ping",
                    "check4", "Check firewall: sudo ufw status"
            ));
            log.error("Redis health check failed", e);
        }

        return status;
    }

    @GetMapping("/kafka")
    public Map<String, Object> checkKafka() {
        Map<String, Object> status = new HashMap<>();

        try {
            // Try to get Kafka metrics
            kafkaTemplate.metrics();
            status.put("status", "UP");
            status.put("message", "Kafka producer is connected");

        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("message", "Kafka connection failed");
            status.put("error", e.getMessage());
            log.error("Kafka health check failed", e);
        }

        return status;
    }

    @GetMapping("/all")
    public Map<String, Object> checkAll() {
        Map<String, Object> status = new HashMap<>();
        status.put("redis", checkRedis());
        status.put("kafka", checkKafka());
        return status;
    }
}