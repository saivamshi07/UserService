package com.platform.userservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserGraphRedisService {

    public static final String GRAPH_FOLLOWING_PREFIX = "graph:user:";
    public static final String GRAPH_FOLLOWING_SUFFIX = ":following";
    public static final String GRAPH_PRIVACY_PREFIX = "graph:user:";
    public static final String GRAPH_PRIVACY_SUFFIX = ":privacy";
    public static final String EMPTY_SENTINEL = "__EMPTY__";

    private final StringRedisTemplate graphRedisTemplate;

    public UserGraphRedisService(@Qualifier("graphRedisTemplate") StringRedisTemplate graphRedisTemplate) {
        this.graphRedisTemplate = graphRedisTemplate;
    }

    /**
     * Records an accepted following relationship in Redis.
     */
    public void addFollowing(UUID followerId, UUID followingId) {
        if (followerId == null || followingId == null) {
            return;
        }
        String key = buildFollowingKey(followerId);
        graphRedisTemplate.opsForSet().remove(key, EMPTY_SENTINEL);
        graphRedisTemplate.opsForSet().add(key, followingId.toString());
        log.debug("Added follow link in Redis: {} -> {}", followerId, followingId);
    }

    /**
     * Removes a following relationship in Redis.
     */
    public void removeFollowing(UUID followerId, UUID followingId) {
        if (followerId == null || followingId == null) {
            return;
        }
        String key = buildFollowingKey(followerId);
        graphRedisTemplate.opsForSet().remove(key, followingId.toString());

        // If set is now empty, add sentinel so it's not mistaken for an uncached cold key
        Long remaining = graphRedisTemplate.opsForSet().size(key);
        if (remaining != null && remaining == 0) {
            graphRedisTemplate.opsForSet().add(key, EMPTY_SENTINEL);
        }
        log.debug("Removed follow link in Redis: {} -> {}", followerId, followingId);
    }

    /**
     * Checks if follower is following target directly in Redis.
     * Returns null if key is not cached (cache miss), true if following, false if not following.
     */
    public Boolean isFollowing(UUID followerId, UUID followingId) {
        if (followerId == null || followingId == null) {
            return false;
        }
        String key = buildFollowingKey(followerId);
        if (!Boolean.TRUE.equals(graphRedisTemplate.hasKey(key))) {
            return null; // Cache miss
        }
        return Boolean.TRUE.equals(graphRedisTemplate.opsForSet().isMember(key, followingId.toString()));
    }

    /**
     * Returns the full set of following UUIDs for a given user.
     * Returns null on cache miss; returns empty set if key exists with only sentinel.
     */
    public Set<UUID> getFollowing(UUID followerId) {
        if (followerId == null) {
            return Collections.emptySet();
        }
        String key = buildFollowingKey(followerId);
        Set<String> members = graphRedisTemplate.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            return null; // Cache miss
        }

        Set<UUID> result = new HashSet<>();
        for (String member : members) {
            if (!EMPTY_SENTINEL.equals(member)) {
                try {
                    result.add(UUID.fromString(member));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return result;
    }

    /**
     * Warms the following set in Redis from database records.
     */
    public void warmFollowing(UUID followerId, Collection<UUID> followingIds) {
        if (followerId == null) {
            return;
        }
        String key = buildFollowingKey(followerId);
        graphRedisTemplate.delete(key);

        if (followingIds == null || followingIds.isEmpty()) {
            graphRedisTemplate.opsForSet().add(key, EMPTY_SENTINEL);
        } else {
            String[] ids = followingIds.stream().map(UUID::toString).toArray(String[]::new);
            graphRedisTemplate.opsForSet().add(key, ids);
        }
        log.debug("Warmed following set for user {} in Redis with {} records", followerId,
                followingIds != null ? followingIds.size() : 0);
    }

    /**
     * Updates privacy status in Redis.
     */
    public void setUserPrivacy(UUID userId, boolean isPrivate) {
        if (userId == null) {
            return;
        }
        String key = buildPrivacyKey(userId);
        graphRedisTemplate.opsForValue().set(key, isPrivate ? "PRIVATE" : "PUBLIC");
        log.debug("Set privacy status in Redis for user {}: {}", userId, isPrivate ? "PRIVATE" : "PUBLIC");
    }

    /**
     * Gets user privacy status from Redis.
     * Returns null on cache miss, true for PRIVATE, false for PUBLIC.
     */
    public Boolean getUserPrivacy(UUID userId) {
        if (userId == null) {
            return null;
        }
        String val = graphRedisTemplate.opsForValue().get(buildPrivacyKey(userId));
        if (val == null) {
            return null;
        }
        return "PRIVATE".equalsIgnoreCase(val);
    }

    /**
     * Batch fetch privacy statuses from Redis.
     */
    public Map<UUID, Boolean> getUserPrivacyBatch(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> keys = userIds.stream().map(this::buildPrivacyKey).toList();
        List<String> values = graphRedisTemplate.opsForValue().multiGet(keys);

        Map<UUID, Boolean> result = new HashMap<>();
        if (values != null) {
            for (int i = 0; i < userIds.size(); i++) {
                String val = values.get(i);
                if (val != null) {
                    result.put(userIds.get(i), "PRIVATE".equalsIgnoreCase(val));
                }
            }
        }
        return result;
    }

    /**
     * Batch warms user privacy statuses in Redis.
     */
    public void warmPrivacyBatch(Map<UUID, Boolean> privacyMap) {
        if (privacyMap == null || privacyMap.isEmpty()) {
            return;
        }
        Map<String, String> stringMap = new HashMap<>();
        privacyMap.forEach((id, isPrivate) ->
                stringMap.put(buildPrivacyKey(id), Boolean.TRUE.equals(isPrivate) ? "PRIVATE" : "PUBLIC"));
        graphRedisTemplate.opsForValue().multiSet(stringMap);
        log.debug("Batch warmed {} user privacy keys in Redis", privacyMap.size());
    }

    /**
     * Evicts user's following graph and privacy from Redis (e.g. account deactivation).
     */
    public void evictUser(UUID userId) {
        if (userId == null) {
            return;
        }
        graphRedisTemplate.delete(List.of(buildFollowingKey(userId), buildPrivacyKey(userId)));
        log.info("Evicted following graph and privacy keys for user {}", userId);
    }

    private String buildFollowingKey(UUID userId) {
        return GRAPH_FOLLOWING_PREFIX + userId + GRAPH_FOLLOWING_SUFFIX;
    }

    private String buildPrivacyKey(UUID userId) {
        return GRAPH_PRIVACY_PREFIX + userId + GRAPH_PRIVACY_SUFFIX;
    }
}
