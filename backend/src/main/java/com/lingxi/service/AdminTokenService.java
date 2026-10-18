package com.lingxi.service;

import com.lingxi.config.LingxiProperties;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminTokenService {
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LingxiProperties properties;

    public AdminTokenService(LingxiProperties properties) {
        this.properties = properties;
    }

    public Token create() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plusSeconds(Math.max(300, properties.adminTokenTtlSeconds()));
        tokens.put(token, expiresAt);
        cleanup();
        return new Token(token, expiresAt);
    }

    public Optional<Instant> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Instant expiresAt = tokens.get(token);
        if (expiresAt == null) return Optional.empty();
        if (expiresAt.isBefore(Instant.now())) {
            tokens.remove(token);
            return Optional.empty();
        }
        return Optional.of(expiresAt);
    }

    public void revoke(String token) {
        if (token != null) tokens.remove(token);
    }

    private void cleanup() {
        if (tokens.size() < 5000) return;
        Instant now = Instant.now();
        tokens.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    public record Token(String value, Instant expiresAt) {
    }
}
