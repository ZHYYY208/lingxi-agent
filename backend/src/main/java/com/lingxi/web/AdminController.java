package com.lingxi.web;

import com.lingxi.config.LingxiProperties;
import com.lingxi.dto.Dtos;
import com.lingxi.service.AdminTokenService;
import com.lingxi.service.ModelConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final LingxiProperties properties;
    private final AdminTokenService tokenService;
    private final ModelConfigService modelConfigService;

    public AdminController(LingxiProperties properties, AdminTokenService tokenService,
                           ModelConfigService modelConfigService) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.modelConfigService = modelConfigService;
    }

    @PostMapping("/login")
    public Dtos.AdminLoginResponse login(@Valid @RequestBody Dtos.AdminLoginRequest request) {
        String configured = properties.adminPassword();
        if (configured == null || configured.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "管理端未启用");
        }
        if (!constantTimeEquals(request.password(), configured)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员密码错误");
        }
        AdminTokenService.Token token = tokenService.create();
        return new Dtos.AdminLoginResponse(token.value(), token.expiresAt());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        tokenService.revoke(token);
        return Map.of("loggedOut", true);
    }

    @GetMapping("/me")
    public Dtos.AdminSessionResponse me(HttpServletRequest request) {
        Object expires = request.getAttribute("lingxi.adminExpiresAt");
        Instant expiresAt = expires instanceof Instant instant ? instant : null;
        return new Dtos.AdminSessionResponse(true, expiresAt);
    }

    @GetMapping("/model")
    public Dtos.ModelConfigView model() {
        return modelConfigService.view();
    }

    @PutMapping("/model")
    public Dtos.ModelConfigView saveModel(@RequestBody Dtos.ModelConfigUpdateRequest request) {
        try {
            return modelConfigService.save(request);
        } catch (IllegalArgumentException exc) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exc.getMessage());
        }
    }

    @PostMapping("/model/test")
    public Dtos.ModelTestResponse testModel(@RequestBody Dtos.ModelTestRequest request) {
        return modelConfigService.test(request);
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", modelConfigService.view());
        result.put("admin_password_is_default", "lingxi-admin".equals(properties.adminPassword()));
        return result;
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
