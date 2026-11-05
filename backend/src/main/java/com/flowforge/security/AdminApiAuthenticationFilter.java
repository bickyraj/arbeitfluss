package com.flowforge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

@Component
public class AdminApiAuthenticationFilter extends OncePerRequestFilter {
    private final AdminTokenService tokenService;

    public AdminApiAuthenticationFilter(AdminTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String mvcPath = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        boolean apiPath = mvcPath.equals("/api") || mvcPath.startsWith("/api/");
        boolean publicPost = "POST".equals(request.getMethod())
                && ("/api/auth/verify".equals(path)
                || path.matches("/api/webhooks/[A-Za-z0-9_-]{1,64}"));
        if (!apiPath || "OPTIONS".equals(request.getMethod()) || publicPost) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpStatus status = tokenService.authenticate(request);
        if (status != HttpStatus.NO_CONTENT) {
            response.setStatus(status.value());
            response.setContentType("application/json");
            if (status == HttpStatus.UNAUTHORIZED) {
                response.setHeader("WWW-Authenticate", "Bearer");
            }
            response.getWriter().write(status == HttpStatus.SERVICE_UNAVAILABLE
                    ? "{\"message\":\"Administrator API authentication is not configured\"}"
                    : "{\"message\":\"Authentication required\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
