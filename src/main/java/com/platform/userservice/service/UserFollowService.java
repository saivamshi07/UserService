package com.platform.userservice.service;

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

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserFollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserRepository userRepository;
    private final PolicyValidatorService policyValidator;

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

        return toFollowResponse(follow, targetUser);
    }

    @Transactional
    public void unfollowUser(UUID followerId, UUID followingId) {
        policyValidator.validateFollowersAllowed();
        userFollowRepository.deleteByIdFollowerIdAndIdFollowingId(followerId, followingId);
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
        } else {
            follow.setStatus(FollowStatus.REJECTED);
            userFollowRepository.delete(follow);
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
