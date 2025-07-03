package com.swiftcart.inventory_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:#{null}}")
    private String redisPassword;

    @Value("${spring.data.redis.connect-timeout:2000ms}")
    private Duration connectTimeout;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        log.info("Configuring Redis connection: host={}, port={}", redisHost, redisPort);

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);

        // Only set password if it's provided and not empty
        if (redisPassword != null && !redisPassword.trim().isEmpty()) {
            log.info("Redis password is configured");
            config.setPassword(RedisPassword.of(redisPassword));
        } else {
            log.info("Redis password is not configured - connecting without authentication");
        }

        // Configure Lettuce client with timeout
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(connectTimeout)
                .shutdownTimeout(Duration.ZERO)
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfig);

        // Test connection after initialization
        factory.setValidateConnection(true);

        return factory;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use JSON serializer for values
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());

        template.setEnableTransactionSupport(false);
        template.afterPropertiesSet();

        // Test connection
        try {
            template.opsForValue().set("test:connection", "OK");
            String result = (String) template.opsForValue().get("test:connection");
            template.delete("test:connection");
            log.info("Redis connection test successful: {}", result);
        } catch (Exception e) {
            log.error("Redis connection test failed: {}", e.getMessage());
        }

        return template;
    }

    @Bean
    public DefaultRedisScript<List> stockReservationScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/reserve_stock.lua"));
        script.setResultType(List.class);
        return script;
    }
}