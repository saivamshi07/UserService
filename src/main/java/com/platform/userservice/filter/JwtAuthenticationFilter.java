package com.platform.userservice.filter;

import com.platform.userservice.context.UserContext;
import com.platform.userservice.service.JwtTokenService;
import com.platform.userservice.util.CookieUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;

    @Value("${gateway.shared-secret:}")
    private String gatewaySharedSecret;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            UUID authenticatedUserId = resolveAuthenticatedUser(request);
            if (authenticatedUserId != null) {
                UserContext.setUserId(authenticatedUserId);
            }

            filterChain.doFilter(request, response);
        } finally {
            // Guarantee thread-local cleanup to prevent memory/security leaks in pooled threads
            UserContext.clear();
        }
    }

    private UUID resolveAuthenticatedUser(HttpServletRequest request) {
        // 1. Check for authenticated Gateway call with matching shared secret
        String incomingGatewaySecret = request.getHeader("X-Gateway-Secret");
        String incomingUserIdHeader = request.getHeader("X-User-Id");

        if (StringUtils.hasText(gatewaySharedSecret)
                && gatewaySharedSecret.equals(incomingGatewaySecret)
                && StringUtils.hasText(incomingUserIdHeader)) {
            try {
                return UUID.fromString(incomingUserIdHeader);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid UUID in trusted X-User-Id header: {}", incomingUserIdHeader);
            }
        }

        // 2. Extract from JWT access_token cookie
        String token = extractTokenFromCookie(request);

        // 3. Fallback: extract from Authorization Bearer header
        if (!StringUtils.hasText(token)) {
            token = extractTokenFromHeader(request);
        }

        if (StringUtils.hasText(token) && jwtTokenService.validateToken(token)) {
            try {
                return jwtTokenService.extractUserId(token);
            } catch (Exception e) {
                log.warn("Failed to extract userId from valid JWT token: {}", e.getMessage());
            }
        }

        return null;
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (CookieUtil.ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String extractTokenFromHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
