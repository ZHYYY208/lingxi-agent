package com.lingxi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_ATTRIBUTE = "lingxi.requestId";
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ObjectMapper mapper;

    public RequestLoggingFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-ID", requestId);
        MDC.put("request_id", requestId);
        long started = System.currentTimeMillis();
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long elapsed = System.currentTimeMillis() - started;
            response.setHeader("X-Response-Time-Ms", String.valueOf(elapsed));
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "request");
            event.put("method", request.getMethod());
            event.put("path", request.getRequestURI());
            event.put("status", status);
            event.put("duration_ms", elapsed);
            event.put("request_id", requestId);
            try {
                log.info(mapper.writeValueAsString(event));
            } catch (Exception ignored) {
                log.info("request method={} path={} status={} duration_ms={} request_id={}",
                        request.getMethod(), request.getRequestURI(), status, elapsed, requestId);
            }
            MDC.remove("request_id");
        }
    }
}
