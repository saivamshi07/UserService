package com.platform.userservice.service;

import com.platform.userservice.dto.LoginRequest;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.dto.UserSearchResponse;
import com.platform.userservice.entity.FollowStatus;
import com.platform.userservice.entity.User;
import com.platform.userservice.entity.UserAuditLog;
import com.platform.userservice.exception.DuplicateResourceException;
import com.platform.userservice.exception.InvalidTokenException;
import com.platform.userservice.exception.ResourceNotFoundException;
import com.platform.userservice.repository.UserAuditLogRepository;
import com.platform.userservice.repository.UserFollowRepository;
import com.platform.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserFollowRepository userFollowRepository;
    private final UserAuditLogRepository auditLogRepository;
    private final PolicyValidatorService policyValidator;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse registerUser(RegisterRequest request, String clientIp, String userAgent) {
        policyValidator.validateRegistration(request);

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username '" + request.getUsername() + "' is already taken");
        }
        if (StringUtils.hasText(request.getEmail()) && userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email '" + request.getEmail() + "' is already in use");
        }
        if (StringUtils.hasText(request.getPhone()) && userRepository.existsByPhone(request.getPhone())) {
            throw new DuplicateResourceException("Phone '" + request.getPhone() + "' is already in use");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .phone(request.getPhone())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isActive(true)
                .build();

        user = userRepository.save(user);

        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(user.getId())
                .activity("LOCAL_REGISTRATION")
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .details(Map.of(
                        "username", user.getUsername(),
                        "timestamp", Instant.now().toString()
                ))
                .build();
        auditLogRepository.save(auditLog);

        return toUserResponse(user, user.getId());
    }

    @Transactional(readOnly = true)
    public UserResponse loginUser(LoginRequest request, String clientIp, String userAgent) {
        String identifier = request.getIdentifier();

        Optional<User> userOpt = userRepository.findByUsername(identifier);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmail(identifier);
        }
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByPhone(identifier);
        }

        User user = userOpt.orElseThrow(() -> new InvalidTokenException("Invalid username/email/phone or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidTokenException("Invalid username/email/phone or password");
        }

        if (!user.isActive()) {
            throw new InvalidTokenException("Account is disabled. Please contact support or reactivate.");
        }

        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(user.getId())
                .activity("LOCAL_LOGIN")
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .details(Map.of(
                        "identifier", identifier,
                        "timestamp", Instant.now().toString()
                ))
                .build();
        auditLogRepository.save(auditLog);

        return toUserResponse(user, user.getId());
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID targetUserId, UUID currentUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + targetUserId));
        return toUserResponse(user, currentUserId);
    }

    @Transactional(readOnly = true)
    public UserResponse getUserByUsername(String username, UUID currentUserId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with username: " + username));
        return toUserResponse(user, currentUserId);
    }

    @Transactional
    public UserResponse updateUserProfile(UUID userId, UpdateUserRequest request) {
        policyValidator.validateUpdateProfile(request);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getPictureUrl() != null) {
            user.setPictureUrl(request.getPictureUrl());
        }
        if (request.getIsPrivate() != null) {
            user.setPrivate(request.getIsPrivate());
        }
        if (request.getIsEmailPrivate() != null) {
            user.setEmailPrivate(request.getIsEmailPrivate());
        }
        if (request.getIsPhonePrivate() != null) {
            user.setPhonePrivate(request.getIsPhonePrivate());
        }
        if (request.getIsPicturePrivate() != null) {
            user.setPicturePrivate(request.getIsPicturePrivate());
        }
        if (request.getPublicProfiles() != null) {
            user.setPublicProfiles(request.getPublicProfiles());
        }

        user = userRepository.save(user);
        return toUserResponse(user, userId);
    }

    @Transactional
    public void deactivateUser(UUID userId, String clientIp, String userAgent) {
        policyValidator.validateUserDisableAllowed();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        user.setActive(false);
        user.setDisabledAt(Instant.now());
        userRepository.save(user);

        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(userId)
                .activity("ACCOUNT_DEACTIVATED")
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .details(Map.of("timestamp", Instant.now().toString()))
                .build();
        auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public Page<UserSearchResponse> searchUsers(String query, Pageable pageable) {
        Page<User> usersPage = userRepository.searchUsers(query, pageable);
        return usersPage.map(u -> {
            long followers = userFollowRepository.countByIdFollowingIdAndStatus(u.getId(), FollowStatus.ACCEPTED);
            return UserSearchResponse.builder()
                    .id(u.getId())
                    .username(u.getUsername())
                    .pictureUrl(u.isPicturePrivate() ? null : u.getPictureUrl())
                    .bio(u.getBio())
                    .isPrivate(u.isPrivate())
                    .followersCount(followers)
                    .build();
        });
    }

    public UserResponse toUserResponse(User user, UUID requesterId) {
        boolean isOwner = requesterId != null && requesterId.equals(user.getId());

        long followersCount = userFollowRepository.countByIdFollowingIdAndStatus(user.getId(), FollowStatus.ACCEPTED);
        long followingCount = userFollowRepository.countByIdFollowerIdAndStatus(user.getId(), FollowStatus.ACCEPTED);

        String visibleEmail = (isOwner || !user.isEmailPrivate()) ? user.getEmail() : null;
        String visiblePhone = (isOwner || !user.isPhonePrivate()) ? user.getPhone() : null;
        String visiblePicture = (isOwner || !user.isPicturePrivate()) ? user.getPictureUrl() : null;

        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(visibleEmail)
                .phone(visiblePhone)
                .bio(user.getBio())
                .pictureUrl(visiblePicture)
                .isPrivate(user.isPrivate())
                .isEmailVerified(user.isEmailVerified())
                .isPhoneVerified(user.isPhoneVerified())
                .publicProfiles(user.getPublicProfiles())
                .followersCount(followersCount)
                .followingCount(followingCount)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
