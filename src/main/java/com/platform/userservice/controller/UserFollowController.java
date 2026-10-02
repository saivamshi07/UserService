package com.platform.userservice.controller;

import com.platform.userservice.dto.UserFollowResponse;
import com.platform.userservice.service.UserFollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserFollowController {

    private final UserFollowService userFollowService;

    @PostMapping("/{id}/follow")
    public ResponseEntity<UserFollowResponse> followUser(
            @PathVariable("id") UUID followingId,
            @RequestHeader("X-User-Id") UUID currentUserId) {
        return ResponseEntity.ok(userFollowService.followUser(currentUserId, followingId));
    }

    @DeleteMapping("/{id}/unfollow")
    public ResponseEntity<Void> unfollowUser(
            @PathVariable("id") UUID followingId,
            @RequestHeader("X-User-Id") UUID currentUserId) {
        userFollowService.unfollowUser(currentUserId, followingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/follows/respond")
    public ResponseEntity<UserFollowResponse> respondToFollowRequest(
            @RequestHeader("X-User-Id") UUID currentUserId,
            @RequestParam("followerId") UUID followerId,
            @RequestParam("accept") boolean accept) {
        return ResponseEntity.ok(userFollowService.respondToFollowRequest(currentUserId, followerId, accept));
    }

    @GetMapping("/{id}/followers")
    public ResponseEntity<Page<UserFollowResponse>> getFollowers(
            @PathVariable("id") UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userFollowService.getFollowers(userId, pageable));
    }

    @GetMapping("/{id}/following")
    public ResponseEntity<Page<UserFollowResponse>> getFollowing(
            @PathVariable("id") UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userFollowService.getFollowing(userId, pageable));
    }
}
