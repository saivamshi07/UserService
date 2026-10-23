package com.platform.userservice.service;

import com.platform.userservice.dto.BatchAccessContextRequest;
import com.platform.userservice.dto.BatchAccessContextResponse;
import com.platform.userservice.dto.UserAccessCheckResponse;
import com.platform.userservice.dto.UserFollowResponse;
import com.platform.userservice.entity.FollowStatus;
import com.platform.userservice.entity.User;
import com.platform.userservice.entity.UserFollow;
import com.platform.userservice.entity.UserFollowId;
import com.platform.userservice.exception.PolicyViolationException;
import com.platform.userservice.exception.ResourceNotFoundException;
import com.platform.userservice.repository.UserFollowRepository;
import com.platform.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@RequiredArgsConstructor
public class UserFollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserRepository userRepository;
    private final PolicyValidatorService policyValidator;
    private final UserGraphRedisService userGraphRedisService;

    @Transactional
    public UserFollowResponse followUser(UUID followerId, UUID followingId) {
        policyValidator.validateFollowersAllowed();

        if (followerId.equals(followingId)) {
            throw new PolicyViolationException("You cannot follow yourself");
        }

        User follower = userRepository.findById(followerId)
                .orElseThrow(() -> new ResourceNotFoundException("Follower user not found: " + followerId));
        User targetUser = userRepository.findById(followingId)
                .orElseThrow(() -> new ResourceNotFoundException("Target user not found: " + followingId));

        if (!targetUser.isActive()) {
            throw new PolicyViolationException("Cannot follow an inactive user");
        }

        FollowStatus initialStatus = targetUser.isPrivate() ? FollowStatus.PENDING : FollowStatus.ACCEPTED;

        UserFollowId followId = new UserFollowId(followerId, followingId);
        UserFollow follow = userFollowRepository.findById(followId)
                .orElseGet(() -> UserFollow.builder()
                        .id(followId)
                        .follower(follower)
                        .following(targetUser)
                        .build());

        follow.setStatus(initialStatus);
        follow = userFollowRepository.save(follow);

        // Synchronously update dedicated Graph Redis on ACCEPTED
        if (initialStatus == FollowStatus.ACCEPTED) {
            userGraphRedisService.addFollowing(followerId, followingId);
        }

        return toFollowResponse(follow, targetUser);
    }

    @Transactional
    public void unfollowUser(UUID followerId, UUID followingId) {
        policyValidator.validateFollowersAllowed();
        userFollowRepository.deleteByIdFollowerIdAndIdFollowingId(followerId, followingId);
        userGraphRedisService.removeFollowing(followerId, followingId);
    }

    @Transactional
    public UserFollowResponse respondToFollowRequest(UUID followingId, UUID followerId, boolean accept) {
        policyValidator.validateFollowersAllowed();

        UserFollowId followId = new UserFollowId(followerId, followingId);
        UserFollow follow = userFollowRepository.findById(followId)
                .orElseThrow(() -> new ResourceNotFoundException("Follow request not found"));

        if (follow.getStatus() != FollowStatus.PENDING) {
            throw new PolicyViolationException("Follow request is not in PENDING state");
        }

        if (accept) {
            follow.setStatus(FollowStatus.ACCEPTED);
            follow = userFollowRepository.save(follow);
            userGraphRedisService.addFollowing(followerId, followingId);
        } else {
            follow.setStatus(FollowStatus.REJECTED);
            userFollowRepository.delete(follow);
            userGraphRedisService.removeFollowing(followerId, followingId);
        }

        return toFollowResponse(follow, follow.getFollower());
    }

    @Transactional(readOnly = true)
    public Page<UserFollowResponse> getFollowers(UUID userId, Pageable pageable) {
        return userFollowRepository.findByIdFollowingIdAndStatus(userId, FollowStatus.ACCEPTED, pageable)
                .map(f -> toFollowResponse(f, f.getFollower()));
    }

    @Transactional(readOnly = true)
    public Page<UserFollowResponse> getFollowing(UUID userId, Pageable pageable) {
        return userFollowRepository.findByIdFollowerIdAndStatus(userId, FollowStatus.ACCEPTED, pageable)
                .map(f -> toFollowResponse(f, f.getFollowing()));
    }

    /**
     * Checks if a viewer is authorized to view an author's private content/reviews.
     * Uses Redis cache with database fallback + on-demand cache warming.
     */
    @Transactional(readOnly = true)
    public UserAccessCheckResponse checkAccess(UUID authorId, UUID viewerId) {
        // 1. Resolve author privacy
        Boolean isPrivate = userGraphRedisService.getUserPrivacy(authorId);
        if (isPrivate == null) {
            User author = userRepository.findById(authorId)
                    .orElseThrow(() -> new ResourceNotFoundException("Author not found: " + authorId));
            isPrivate = author.isPrivate();
            userGraphRedisService.setUserPrivacy(authorId, isPrivate);
        }

        // 2. Self-view bypass
        if (viewerId != null && viewerId.equals(authorId)) {
            return UserAccessCheckResponse.builder()
                    .authorId(authorId)
                    .viewerId(viewerId)
                    .isPrivate(isPrivate)
                    .isFollowing(true)
                    .canView(true)
                    .build();
        }

        // 3. Public author bypass
        if (!isPrivate) {
            return UserAccessCheckResponse.builder()
                    .authorId(authorId)
                    .viewerId(viewerId)
                    .isPrivate(false)
                    .isFollowing(false)
                    .canView(true)
                    .build();
        }

        // 4. Private author with unauthenticated/guest viewer
        if (viewerId == null) {
            return UserAccessCheckResponse.builder()
                    .authorId(authorId)
                    .viewerId(null)
                    .isPrivate(true)
                    .isFollowing(false)
                    .canView(false)
                    .build();
        }

        // 5. Private author with authenticated viewer: check following status
        Boolean isFollowing = userGraphRedisService.isFollowing(viewerId, authorId);
        if (isFollowing == null) {
            // Cache miss: warm following set for viewer
            List<UUID> followingIds = userFollowRepository.findFollowingIdsByFollowerIdAndStatus(
                    viewerId, FollowStatus.ACCEPTED);
            userGraphRedisService.warmFollowing(viewerId, followingIds);
            isFollowing = followingIds.contains(authorId);
        }

        return UserAccessCheckResponse.builder()
                .authorId(authorId)
                .viewerId(viewerId)
                .isPrivate(true)
                .isFollowing(isFollowing)
                .canView(isFollowing)
                .build();
    }

    /**
     * Batch retrieves access context for movie-service:
     * - Batched author privacy flags
     * - Viewer's following set
     * Warms any cache misses in Redis directly.
     */
    @Transactional(readOnly = true)
    public BatchAccessContextResponse getBatchAccessContext(BatchAccessContextRequest request) {
        List<UUID> authorIds = request.getAuthorIds() != null ? request.getAuthorIds() : Collections.emptyList();
        UUID viewerId = request.getViewerId();

        // 1. Resolve author privacy in batch
        Map<UUID, Boolean> authorPrivacy = new HashMap<>(userGraphRedisService.getUserPrivacyBatch(authorIds));
        List<UUID> missingAuthorIds = authorIds.stream()
                .filter(id -> !authorPrivacy.containsKey(id))
                .distinct()
                .toList();

        if (!missingAuthorIds.isEmpty()) {
            List<Object[]> rows = userRepository.findPrivacyStatusByIds(missingAuthorIds);
            Map<UUID, Boolean> warmedMap = new HashMap<>();
            for (Object[] row : rows) {
                UUID id = (UUID) row[0];
                Boolean isPrivate = (Boolean) row[1];
                warmedMap.put(id, isPrivate != null ? isPrivate : false);
            }
            // For any authors not in DB, default to false (public)
            for (UUID id : missingAuthorIds) {
                warmedMap.putIfAbsent(id, false);
            }
            userGraphRedisService.warmPrivacyBatch(warmedMap);
            authorPrivacy.putAll(warmedMap);
        }

        // 2. Resolve viewer following set
        Set<UUID> viewerFollowing;
        if (viewerId == null) {
            viewerFollowing = Collections.emptySet();
        } else {
            Set<UUID> cachedFollowing = userGraphRedisService.getFollowing(viewerId);
            if (cachedFollowing != null) {
                viewerFollowing = cachedFollowing;
            } else {
                List<UUID> dbFollowing = userFollowRepository.findFollowingIdsByFollowerIdAndStatus(
                        viewerId, FollowStatus.ACCEPTED);
                userGraphRedisService.warmFollowing(viewerId, dbFollowing);
                viewerFollowing = new HashSet<>(dbFollowing);
            }
        }

        return BatchAccessContextResponse.builder()
                .viewerId(viewerId)
                .viewerFollowing(viewerFollowing)
                .authorPrivacy(authorPrivacy)
                .build();
    }

    private UserFollowResponse toFollowResponse(UserFollow follow, User otherUser) {
        return UserFollowResponse.builder()
                .followerId(follow.getId().getFollowerId())
                .followingId(follow.getId().getFollowingId())
                .username(otherUser.getUsername())
                .pictureUrl(otherUser.isPicturePrivate() ? null : otherUser.getPictureUrl())
                .status(follow.getStatus())
                .createdAt(follow.getCreatedAt())
                .build();
    }
}
