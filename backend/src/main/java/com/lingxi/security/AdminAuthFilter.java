package com.lingxi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.dto.Dtos;
import com.lingxi.service.AdminTokenService;
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

@Component
@Order(4)
public class AdminAuthFilter extends OncePerRequestFilter {
    private final AdminTokenService tokenService;
    private final ObjectMapper mapper;

    public AdminAuthFilter(AdminTokenService tokenService, ObjectMapper mapper) {
        this.tokenService = tokenService;
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
        boolean protectedPath = path.equals("/api/documents")
                || path.startsWith("/api/documents/")
                || path.startsWith("/api/admin/")
                || path.equals("/api/admin");
        boolean loginPath = path.equals("/api/admin/login");
        if (!protectedPath || loginPath) {
            chain.doFilter(request, response);
            return;
        }
        String token = request.getHeader("X-Admin-Token");
        var expiry = tokenService.validate(token);
        if (expiry.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"lingxi-admin\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Object requestId = request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
            Dtos.ErrorResponse body = new Dtos.ErrorResponse(
                    "需要管理员登录", "ADMIN_UNAUTHORIZED", requestId == null ? "" : requestId.toString()
            );
            response.getWriter().write(mapper.writeValueAsString(body));
            return;
        }
        request.setAttribute("lingxi.adminExpiresAt", expiry.get());
        chain.doFilter(request, response);
    }
}
