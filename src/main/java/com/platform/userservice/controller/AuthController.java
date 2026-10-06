package com.platform.userservice.controller;

import com.platform.userservice.dto.GoogleAuthRequest;
import com.platform.userservice.dto.LoginRequest;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.entity.User;
import com.platform.userservice.exception.InvalidTokenException;
import com.platform.userservice.repository.UserRepository;
import com.platform.userservice.service.GoogleAuthService;
import com.platform.userservice.service.JwtTokenService;
import com.platform.userservice.service.RedisSessionService;
import com.platform.userservice.service.UserService;
import com.platform.userservice.util.CookieUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final GoogleAuthService googleAuthService;
    private final JwtTokenService jwtTokenService;
    private final RedisSessionService redisSessionService;
    private final CookieUtil cookieUtil;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        UserResponse userResponse = userService.registerUser(request, clientIp, userAgent);
        return buildAuthResponse(userResponse, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        UserResponse userResponse = userService.loginUser(request, clientIp, userAgent);
        return buildAuthResponse(userResponse, HttpStatus.OK);
    }

    @PostMapping("/google")
    public ResponseEntity<UserResponse> googleLogin(
            @Valid @RequestBody GoogleAuthRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        User user = googleAuthService.authenticateOrProvisionUser(request.getIdToken(), clientIp, userAgent);
        UserResponse userResponse = userService.toUserResponse(user, user.getId());
        return buildAuthResponse(userResponse, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<UserResponse> refreshToken(
            @CookieValue(name = CookieUtil.REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || !jwtTokenService.validateRefreshToken(refreshToken)) {
            throw new InvalidTokenException("Invalid or expired refresh token. Please log in again.");
        }

        String jti = jwtTokenService.extractJti(refreshToken);
        if (!redisSessionService.isRefreshTokenActive(jti)) {
            throw new InvalidTokenException("Refresh token session is invalid or has been revoked.");
        }

        UUID userId = jwtTokenService.extractUserId(refreshToken);
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidTokenException("User session is no longer active."));

        // Rotate token: revoke old jti session in Redis
        redisSessionService.revokeRefreshToken(jti);

        UserResponse userResponse = userService.toUserResponse(user, user.getId());
        return buildAuthResponse(userResponse, HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = CookieUtil.REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && jwtTokenService.validateRefreshToken(refreshToken)) {
            try {
                String jti = jwtTokenService.extractJti(refreshToken);
                redisSessionService.revokeRefreshToken(jti);
            } catch (Exception ignored) {
            }
        }

        ResponseCookie clearAccess = cookieUtil.createDeleteCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/");
        ResponseCookie clearRefresh = cookieUtil.createDeleteCookie(CookieUtil.REFRESH_TOKEN_COOKIE, "/api/v1/auth");

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearAccess.toString())
                .header(HttpHeaders.SET_COOKIE, clearRefresh.toString())
                .build();
    }

    private ResponseEntity<UserResponse> buildAuthResponse(UserResponse userResponse, HttpStatus status) {
        String accessToken = jwtTokenService.generateAccessToken(userResponse.getId(), userResponse.getUsername());
        String refreshToken = jwtTokenService.generateRefreshToken(userResponse.getId());

        // Persist refresh token in Redis with 7-day TTL
        String jti = jwtTokenService.extractJti(refreshToken);
        redisSessionService.saveRefreshToken(
                userResponse.getId(),
                jti,
                Duration.ofSeconds(jwtTokenService.getRefreshTokenExpirationSeconds())
        );

        ResponseCookie accessCookie = cookieUtil.createAccessTokenCookie(
                accessToken, jwtTokenService.getAccessTokenExpirationSeconds());
        ResponseCookie refreshCookie = cookieUtil.createRefreshTokenCookie(
                refreshToken, jwtTokenService.getRefreshTokenExpirationSeconds());

        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(userResponse);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
