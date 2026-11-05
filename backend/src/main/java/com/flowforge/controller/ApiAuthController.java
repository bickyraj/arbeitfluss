package com.flowforge.controller;

import com.flowforge.security.AdminTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class ApiAuthController {
    private final AdminTokenService tokenService;

    public ApiAuthController(AdminTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(HttpServletRequest request) {
        HttpStatus status = tokenService.authenticate(request);
        if (status == HttpStatus.NO_CONTENT) {
            return ResponseEntity.noContent().build();
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return ResponseEntity.status(status).body(Map.of(
                    "message", "Administrator API authentication is not configured"));
        }
        return ResponseEntity.status(status).header("WWW-Authenticate", "Bearer").build();
    }
}
