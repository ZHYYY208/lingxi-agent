package com.lingxi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.config.LingxiProperties;
import com.lingxi.dto.Dtos;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(3)
public class ApiKeyRateLimitFilter extends OncePerRequestFilter {
    private final LingxiProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public ApiKeyRateLimitFilter(LingxiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        boolean apiPath = path.startsWith("/api/");
        boolean exempt = path.equals("/api/health") || path.startsWith("/assets/")
                || path.startsWith("/favicon") || path.equals("/");
        boolean adminProtected = path.startsWith("/api/admin") || path.startsWith("/api/documents");
        if (apiPath && !exempt && properties.authEnabled() && !adminProtected) {
            String supplied = request.getHeader("X-API-Key");
            if (supplied == null || supplied.isBlank()) {
                String authorization = request.getHeader("Authorization");
                if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    supplied = authorization.substring(7).strip();
                }
            }
            if (!constantTimeEquals(supplied, properties.apiKey())) {
                writeError(request, response, 401, "需要有效的 API Key", "UNAUTHORIZED");
                return;
            }
        }
        if (apiPath) {
            int limit = path.startsWith("/api/chat")
                    ? properties.chatRateLimitPerMinute() : properties.rateLimitPerMinute();
            String client = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
            String key = client + ":" + (path.startsWith("/api/chat") ? "chat" : "api");
            long now = System.currentTimeMillis();
            Deque<Long> bucket = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            long retryAfter;
            synchronized (bucket) {
                while (!bucket.isEmpty() && bucket.peekFirst() < now - 60_000) bucket.pollFirst();
                if (bucket.size() >= limit) {
                    retryAfter = Math.max(1, (60_000 - (now - bucket.peekFirst())) / 1000);
                } else {
                    bucket.addLast(now);
                    retryAfter = 0;
                }
            }
            if (retryAfter > 0) {
                response.setHeader("Retry-After", String.valueOf(retryAfter));
                writeError(request, response, 429, "请求过于频繁，请稍后再试", "RATE_LIMITED");
                return;
            }
            if (buckets.size() > 5000) {
                buckets.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            }
        }
        chain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status,
                            String detail, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String requestId = String.valueOf(request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE));
        Dtos.ErrorResponse body = new Dtos.ErrorResponse(detail, code, requestId);
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
