package com.platform.userservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSessionService {

    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";
    private static final String USER_SESSIONS_PREFIX = "user_sessions:";
    private static final String PASSWORD_RESET_TOKEN_PREFIX = "password_reset:";
    private static final String USER_PASSWORD_RESET_PREFIX = "password_reset_user:";
    private static final String PASSWORD_RESET_COOLDOWN_PREFIX = "password_reset_cooldown:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Stores an active refresh token session with a 7-day TTL and tracks it under the user's active sessions.
     */
    public void saveRefreshToken(UUID userId, String jti, Duration ttl) {
        String tokenKey = REFRESH_TOKEN_PREFIX + jti;
        String userSessionsKey = USER_SESSIONS_PREFIX + userId;

        redisTemplate.opsForValue().set(tokenKey, userId.toString(), ttl);
        redisTemplate.opsForSet().add(userSessionsKey, jti);
        redisTemplate.expire(userSessionsKey, ttl);

        log.debug("Saved refresh token session for user {} with jti {}", userId, jti);
    }

    /**
     * Checks if the refresh token is currently active and not revoked in Redis.
     */
    public boolean isRefreshTokenActive(String jti) {
        if (jti == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(REFRESH_TOKEN_PREFIX + jti));
    }

    /**
     * Revokes a single refresh token session (e.g. on user logout).
     */
    public void revokeRefreshToken(String jti) {
        if (jti == null) {
            return;
        }
        String tokenKey = REFRESH_TOKEN_PREFIX + jti;
        String userIdStr = redisTemplate.opsForValue().get(tokenKey);

        redisTemplate.delete(tokenKey);

        if (userIdStr != null) {
            redisTemplate.opsForSet().remove(USER_SESSIONS_PREFIX + userIdStr, jti);
        }

        log.info("Revoked refresh token with jti {}", jti);
    }

    /**
     * Revokes all active refresh token sessions for a user (e.g. on password reset or account deactivation).
     */
    public void revokeAllUserSessions(UUID userId) {
        if (userId == null) {
            return;
        }
        String userSessionsKey = USER_SESSIONS_PREFIX + userId;
        Set<String> activeJtis = redisTemplate.opsForSet().members(userSessionsKey);

        if (activeJtis != null && !activeJtis.isEmpty()) {
            for (String jti : activeJtis) {
                redisTemplate.delete(REFRESH_TOKEN_PREFIX + jti);
            }
        }

        redisTemplate.delete(userSessionsKey);
        log.info("Revoked all active sessions for user {}", userId);
    }

    /**
     * Stores a password reset token in Redis with a short TTL (e.g. 15 minutes).
     * Invalidates any previously active reset token issued for this user.
     */
    public void savePasswordResetToken(String token, UUID userId, Duration ttl) {
        if (token == null || userId == null) {
            return;
        }
        String userKey = USER_PASSWORD_RESET_PREFIX + userId;
        String oldToken = redisTemplate.opsForValue().get(userKey);
        if (oldToken != null) {
            redisTemplate.delete(PASSWORD_RESET_TOKEN_PREFIX + oldToken);
        }

        String tokenKey = PASSWORD_RESET_TOKEN_PREFIX + token;
        redisTemplate.opsForValue().set(tokenKey, userId.toString(), ttl);
        redisTemplate.opsForValue().set(userKey, token, ttl);
        log.debug("Saved password reset token for user {}", userId);
    }

    /**
     * Retrieves the active reset token for a user, or null if none exists.
     */
    public String getActivePasswordResetToken(UUID userId) {
        if (userId == null) {
            return null;
        }
        return redisTemplate.opsForValue().get(USER_PASSWORD_RESET_PREFIX + userId);
    }

    /**
     * Retrieves the user ID associated with an active password reset token, or null if expired/invalid.
     */
    public UUID getUserIdByPasswordResetToken(String token) {
        if (token == null) {
            return null;
        }
        String key = PASSWORD_RESET_TOKEN_PREFIX + token;
        String userIdStr = redisTemplate.opsForValue().get(key);
        if (userIdStr == null) {
            return null;
        }
        try {
            return UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID in password reset token value: {}", userIdStr);
            return null;
        }
    }

    /**
     * Deletes a password reset token after it has been used.
     */
    public void deletePasswordResetToken(String token) {
        if (token == null) {
            return;
        }
        String tokenKey = PASSWORD_RESET_TOKEN_PREFIX + token;
        String userIdStr = redisTemplate.opsForValue().get(tokenKey);
        if (userIdStr != null) {
            redisTemplate.delete(USER_PASSWORD_RESET_PREFIX + userIdStr);
        }
        redisTemplate.delete(tokenKey);
        log.debug("Deleted password reset token");
    }

    /**
     * Checks if a password reset cooldown is active for this user ID.
     */
    public boolean isPasswordResetInCooldown(UUID userId) {
        if (userId == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(PASSWORD_RESET_COOLDOWN_PREFIX + userId));
    }

    /**
     * Sets a cooldown window (e.g. 60 seconds) to prevent spamming/inbox-bombing against a victim user.
     */
    public void setPasswordResetCooldown(UUID userId, Duration cooldown) {
        if (userId == null) {
            return;
        }
        redisTemplate.opsForValue().set(PASSWORD_RESET_COOLDOWN_PREFIX + userId, "1", cooldown);
    }
}
