package com.platform.userservice.controller;

import com.platform.userservice.context.UserContext;
import com.platform.userservice.dto.BatchAccessContextRequest;
import com.platform.userservice.dto.BatchAccessContextResponse;
import com.platform.userservice.dto.FollowDecisionRequest;
import com.platform.userservice.dto.FollowUserRequest;
import com.platform.userservice.dto.UserAccessCheckResponse;
import com.platform.userservice.dto.UserFollowResponse;
import com.platform.userservice.service.UserFollowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserFollowController {

    private final UserFollowService userFollowService;

    /**
     * Follow a target user via JSON request body.
     */
    @PostMapping("/follows")
    public ResponseEntity<UserFollowResponse> followUser(@Valid @RequestBody FollowUserRequest request) {
        UUID currentUserId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(userFollowService.followUser(currentUserId, request.getTargetUserId()));
    }

    /**
     * Legacy/path-based follow endpoint for convenience.
     */
    @PostMapping("/{id}/follow")
    public ResponseEntity<UserFollowResponse> followUserByPath(@PathVariable("id") UUID followingId) {
        UUID currentUserId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(userFollowService.followUser(currentUserId, followingId));
    }

    /**
     * Unfollow a target user.
     */
    @DeleteMapping("/follows/{targetUserId}")
    public ResponseEntity<Void> unfollowUser(@PathVariable("targetUserId") UUID targetUserId) {
        UUID currentUserId = UserContext.getRequiredUserId();
        userFollowService.unfollowUser(currentUserId, targetUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Legacy/path-based unfollow endpoint for convenience.
     */
    @DeleteMapping("/{id}/unfollow")
    public ResponseEntity<Void> unfollowUserByPath(@PathVariable("id") UUID followingId) {
        UUID currentUserId = UserContext.getRequiredUserId();
        userFollowService.unfollowUser(currentUserId, followingId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Respond to a pending follow request (accept or reject) using a JSON body.
     */
    @PostMapping("/follows/respond")
    public ResponseEntity<UserFollowResponse> respondToFollowRequest(@Valid @RequestBody FollowDecisionRequest request) {
        UUID currentUserId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(userFollowService.respondToFollowRequest(
                currentUserId,
                request.getFollowerId(),
                request.getAccept()
        ));
    }

    /**
     * Paginated list of users following the target user.
     */
    @GetMapping("/{id}/followers")
    public ResponseEntity<Page<UserFollowResponse>> getFollowers(
            @PathVariable("id") UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userFollowService.getFollowers(userId, pageable));
    }

    /**
     * Paginated list of users that the target user is following.
     */
    @GetMapping("/{id}/following")
    public ResponseEntity<Page<UserFollowResponse>> getFollowing(
            @PathVariable("id") UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userFollowService.getFollowing(userId, pageable));
    }

    /**
     * Single relationship check: tests if a viewer is authorized to view an author's private reviews/ratings.
     */
    @GetMapping("/{id}/access-check")
    public ResponseEntity<UserAccessCheckResponse> checkAccess(
            @PathVariable("id") UUID authorId,
            @RequestParam(name = "viewerId", required = false) UUID viewerId) {
        UUID effectiveViewerId = viewerId != null ? viewerId : UserContext.getUserId();
        return ResponseEntity.ok(userFollowService.checkAccess(authorId, effectiveViewerId));
    }

    /**
     * Batch access context endpoint for downstream services (e.g. movie-service):
     * Resolves author privacy flags and viewer following sets in bulk, warming the dedicated Redis cache.
     */
    @PostMapping("/access-context")
    public ResponseEntity<BatchAccessContextResponse> getBatchAccessContext(
            @RequestBody BatchAccessContextRequest request) {
        if (request.getViewerId() == null && UserContext.getUserId() != null) {
            request.setViewerId(UserContext.getUserId());
        }
        return ResponseEntity.ok(userFollowService.getBatchAccessContext(request));
    }
}
