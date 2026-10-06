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
}
