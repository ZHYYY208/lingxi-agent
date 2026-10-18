package com.lingxi.security;

import com.lingxi.config.LingxiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(2)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private final LingxiProperties properties;

    public SecurityHeadersFilter(LingxiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self'; font-src 'self' data:; connect-src 'self'; frame-ancestors 'self'");
        String path = request.getRequestURI();
        if (path.startsWith("/assets/")) {
            response.setHeader("Cache-Control", "production".equalsIgnoreCase(properties.appEnv())
                    ? "public, max-age=3600" : "no-cache");
        } else {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
