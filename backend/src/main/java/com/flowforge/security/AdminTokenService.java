package com.flowforge.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.regex.Pattern;

@Service
public class AdminTokenService {
    private static final Pattern BEARER = Pattern.compile("Bearer ([A-Za-z0-9._~+/-]+=*)", Pattern.CASE_INSENSITIVE);
    private final byte[] adminToken;

    public AdminTokenService(@Value("${flowforge.security.admin-token:}") String adminToken) {
        this.adminToken = adminToken == null || adminToken.isBlank()
                ? null : adminToken.getBytes(StandardCharsets.UTF_8);
    }

    public HttpStatus authenticate(HttpServletRequest request) {
        if (adminToken == null) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1) {
            return HttpStatus.UNAUTHORIZED;
        }
        var matcher = BEARER.matcher(headers.getFirst());
        if (!matcher.matches()) {
            return HttpStatus.UNAUTHORIZED;
        }
        return MessageDigest.isEqual(adminToken, matcher.group(1).getBytes(StandardCharsets.UTF_8))
                ? HttpStatus.NO_CONTENT : HttpStatus.UNAUTHORIZED;
    }
}
