package com.lingxi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.config.LingxiProperties;
import com.lingxi.dto.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
public class SessionCache {
    private static final Logger log = LoggerFactory.getLogger(SessionCache.class);
    private final LingxiProperties properties;
    private final ObjectMapper mapper;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public SessionCache(LingxiProperties properties, ObjectMapper mapper, ObjectProvider<StringRedisTemplate> redisProvider) {
        this.properties = properties;
        this.mapper = mapper;
        this.redisProvider = redisProvider;
    }

    public boolean enabled() {
        return properties.redisEnabled();
    }

    public Optional<List<Dtos.MessageDto>> get(String sessionId) {
        if (!enabled()) return Optional.empty();
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return Optional.empty();
        try {
            String value = redis.opsForValue().get(key(sessionId));
            if (value == null || value.isBlank()) return Optional.empty();
            return Optional.of(mapper.readValue(value, new TypeReference<>() {
            }));
        } catch (Exception exc) {
            log.debug("Redis session cache read failed: {}", exc.getMessage());
            return Optional.empty();
        }
    }

    public void put(String sessionId, List<Dtos.MessageDto> messages) {
        if (!enabled()) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            redis.opsForValue().set(key(sessionId), mapper.writeValueAsString(messages),
                    Duration.ofSeconds(properties.sessionCacheTtlSeconds()));
        } catch (Exception exc) {
            log.debug("Redis session cache write failed: {}", exc.getMessage());
        }
    }

    public void invalidate(String sessionId) {
        if (!enabled()) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            redis.delete(key(sessionId));
        } catch (Exception exc) {
            log.debug("Redis session cache invalidation failed: {}", exc.getMessage());
        }
    }

    public boolean healthy() {
        if (!enabled()) return false;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) return false;
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception exc) {
            return false;
        }
    }

    private String key(String sessionId) {
        return "lingxi:messages:" + sessionId;
    }
}
