package com.platform.userservice.service;

import com.platform.userservice.dto.ChangePasswordRequest;
import com.platform.userservice.dto.ForgotPasswordRequest;
import com.platform.userservice.dto.ForgotPasswordResponse;
import com.platform.userservice.dto.LoginRequest;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.ResetPasswordRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.dto.UserSearchResponse;
import com.platform.userservice.entity.FollowStatus;
import com.platform.userservice.entity.User;
import com.platform.userservice.entity.UserAuditLog;
import com.platform.userservice.event.PasswordResetRequestedEvent;
import com.platform.userservice.event.UserDeactivatedEvent;
import com.platform.userservice.event.UserRegisteredEvent;
import com.platform.userservice.exception.DuplicateResourceException;
import com.platform.userservice.exception.InvalidTokenException;
import com.platform.userservice.exception.PolicyViolationException;
import com.platform.userservice.exception.ResourceNotFoundException;
import com.platform.userservice.repository.UserAuditLogRepository;
import com.platform.userservice.repository.UserFollowRepository;
import com.platform.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
    private final RedisSessionService redisSessionService;
    private final AvatarStorageService avatarStorageService;
    private final OutboxService outboxService;
    private final UserGraphRedisService userGraphRedisService;

    @Value("${kafka.topics.user-registered:user.registered.v1}")
    private String userRegisteredTopic;

    @Value("${kafka.topics.user-deactivated:user.deactivated.v1}")
    private String userDeactivatedTopic;

    @Value("${kafka.topics.password-reset-requested:user.password-reset-requested.v1}")
    private String passwordResetTopic;

    @Transactional
    public UserResponse registerUser(RegisterRequest request, String clientIp, String userAgent) {
        policyValidator.validateRegistration(request);

        String email = StringUtils.hasText(request.getEmail()) ? request.getEmail() : null;
        String phone = StringUtils.hasText(request.getPhone()) ? request.getPhone() : null;

        // Single DB round-trip check for existing identifiers
        List<Object[]> duplicates = userRepository.findExistingIdentifiers(request.getUsername(), email, phone);
        if (!duplicates.isEmpty()) {
            for (Object[] row : duplicates) {
                String existingUsername = (String) row[0];
                String existingEmail = (String) row[1];
                String existingPhone = (String) row[2];

                if (request.getUsername().equalsIgnoreCase(existingUsername)) {
                    throw new DuplicateResourceException("Username '" + request.getUsername() + "' is already taken");
                }
                if (email != null && email.equalsIgnoreCase(existingEmail)) {
                    throw new DuplicateResourceException("Email '" + request.getEmail() + "' is already in use");
                }
                if (phone != null && phone.equals(existingPhone)) {
                    throw new DuplicateResourceException("Phone '" + request.getPhone() + "' is already in use");
                }
            }
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isActive(true)
                .build();

        user = userRepository.save(user);

        // Update dedicated Graph Redis with user's initial privacy status
        userGraphRedisService.setUserPrivacy(user.getId(), user.isPrivate());

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

        // Transactional Outbox pattern: Save domain event in the same atomic DB transaction
        outboxService.saveEvent(
                "USER",
                user.getId().toString(),
                "USER_REGISTERED",
                userRegisteredTopic,
                UserRegisteredEvent.builder()
                        .eventId(UUID.randomUUID())
                        .userId(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .phone(user.getPhone())
                        .timestamp(Instant.now())
                        .build()
        );

        return toUserResponse(user, user.getId());
    }

    @Transactional
    public UserResponse loginUser(LoginRequest request, String clientIp, String userAgent) {
        String identifier = request.getIdentifier();

        // Single index-backed query across username, email, and phone
        Optional<User> userOpt = userRepository.findByUsername(identifier);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmail(identifier);
        }
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByPhone(identifier);
        }

        if (userOpt.isEmpty()) {
            throw new InvalidTokenException("Invalid username or password");
        }

        User user = userOpt.get();

        if (!user.isActive()) {
            throw new PolicyViolationException("Account is disabled or deactivated");
        }

        if (user.getPasswordHash() == null) {
            throw new PolicyViolationException("Account does not have a local password configured (e.g. registered via Google OAuth)");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidTokenException("Invalid username or password");
        }

        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(user.getId())
                .activity("LOGIN_SUCCESS")
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

    @Transactional
    public ForgotPasswordResponse requestPasswordReset(ForgotPasswordRequest request, String clientIp, String userAgent) {
        String identifier = request.getIdentifier();

        Optional<User> userOpt = userRepository.findByUsername(identifier);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmail(identifier);
        }
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByPhone(identifier);
        }

        // Standard generic message for anti-enumeration: never disclose whether the account exists
        String genericMessage = "If an active account exists with that identifier, instructions to reset your password have been sent.";

        if (userOpt.isEmpty() || !userOpt.get().isActive()) {
            log.warn("Password reset requested for non-existent or inactive identifier: {}", identifier);
            return ForgotPasswordResponse.builder()
                    .message(genericMessage)
                    .build();
        }

        User user = userOpt.get();

        // Rate-limiting cooldown per victim/user ID to prevent spamming / inbox-bombing
        if (redisSessionService.isPasswordResetInCooldown(user.getId())) {
            log.warn("Password reset for user {} ignored due to active cooldown window", user.getId());
            return ForgotPasswordResponse.builder()
                    .message(genericMessage)
                    .build();
        }

        String resetToken = UUID.randomUUID().toString();
        redisSessionService.savePasswordResetToken(resetToken, user.getId(), Duration.ofMinutes(15));
        redisSessionService.setPasswordResetCooldown(user.getId(), Duration.ofSeconds(60));

        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(user.getId())
                .activity("PASSWORD_RESET_REQUESTED")
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .details(Map.of(
                        "identifier", identifier,
                        "timestamp", Instant.now().toString()
                ))
                .build();
        auditLogRepository.save(auditLog);

        // Transactional Outbox pattern: Save domain event for out-of-band delivery (mail/SMS worker)
        PasswordResetRequestedEvent event = PasswordResetRequestedEvent.builder()
                .eventId(UUID.randomUUID())
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .resetToken(resetToken)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(15)))
                .timestamp(Instant.now())
                .build();

        outboxService.saveEvent(
                "USER",
                user.getId().toString(),
                "PASSWORD_RESET_REQUESTED",
                passwordResetTopic,
                event
        );

        log.info("Password reset token generated and published to outbox for user {}", user.getId());

        return ForgotPasswordResponse.builder()
                .message(genericMessage)
                .build();
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request, String clientIp, String userAgent) {
        UUID userId = redisSessionService.getUserIdByPasswordResetToken(request.getResetToken());
        if (userId == null) {
            throw new InvalidTokenException("Invalid or expired password reset token.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (!user.isActive()) {
            throw new PolicyViolationException("Cannot reset password for an inactive or disabled account.");
        }

        // Check if new password is identical to old password
        if (user.getPasswordHash() != null && passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new PolicyViolationException("New password cannot be the same as the existing password.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // One-time use: consume the reset token
        redisSessionService.deletePasswordResetToken(request.getResetToken());

        // Revoke all existing sessions across all user devices
        redisSessionService.revokeAllUserSessions(userId);

        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(user.getId())
                .activity("PASSWORD_RESET_COMPLETED")
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .details(Map.of("timestamp", Instant.now().toString()))
                .build();
        auditLogRepository.save(auditLog);

        log.info("Password reset successfully completed for user {}", user.getId());
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request, String clientIp, String userAgent) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (!user.isActive()) {
            throw new PolicyViolationException("Cannot change password for an inactive account.");
        }

        if (user.getPasswordHash() == null) {
            throw new PolicyViolationException("Account has no existing password (e.g. Google OAuth account). Use reset password instead.");
        }

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new PolicyViolationException("Current password does not match.");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new PolicyViolationException("New password cannot be the same as the current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Revoke all other sessions across devices
        redisSessionService.revokeAllUserSessions(userId);

        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(user.getId())
                .activity("PASSWORD_CHANGED")
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .details(Map.of("timestamp", Instant.now().toString()))
                .build();
        auditLogRepository.save(auditLog);

        log.info("Password changed successfully for user {}", user.getId());
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID targetUserId, UUID currentUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + targetUserId));
        return toUserResponse(user, currentUserId);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getUsersByIds(List<UUID> userIds, UUID currentUserId) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> distinctIds = userIds.stream().distinct().toList();
        List<User> users = userRepository.findAllById(distinctIds);

        return users.stream()
                .filter(u -> u.isActive() || (currentUserId != null && currentUserId.equals(u.getId())))
                .map(user -> toUserResponse(user, currentUserId))
                .toList();
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

        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsername(request.getUsername())) {
                throw new DuplicateResourceException("Username '" + request.getUsername() + "' is already taken");
            }
            user.setUsername(request.getUsername());
        }

        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getPictureUrl() != null && !request.getPictureUrl().equals(user.getPictureUrl())) {
            String oldPictureUrl = user.getPictureUrl();
            user.setPictureUrl(request.getPictureUrl());

            // Clean up previous avatar from S3 / MinIO storage
            if (StringUtils.hasText(oldPictureUrl)) {
                avatarStorageService.deleteAvatar(oldPictureUrl);
            }
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

        // Update dedicated Graph Redis if privacy setting changed
        if (request.getIsPrivate() != null) {
            userGraphRedisService.setUserPrivacy(user.getId(), user.isPrivate());
        }

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

        // Instantly revoke all active sessions across all devices in Redis
        redisSessionService.revokeAllUserSessions(userId);

        // Evict following and privacy entries from dedicated Graph Redis
        userGraphRedisService.evictUser(userId);

        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(userId)
                .activity("ACCOUNT_DEACTIVATED")
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .details(Map.of("timestamp", Instant.now().toString()))
                .build();
        auditLogRepository.save(auditLog);

        // Transactional Outbox pattern: Save domain event in the same atomic DB transaction
        outboxService.saveEvent(
                "USER",
                user.getId().toString(),
                "USER_DEACTIVATED",
                userDeactivatedTopic,
                UserDeactivatedEvent.builder()
                        .eventId(UUID.randomUUID())
                        .userId(user.getId())
                        .username(user.getUsername())
                        .disabledAt(user.getDisabledAt())
                        .build()
        );
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
                .isEmailPrivate(user.isEmailPrivate())
                .isPhonePrivate(user.isPhonePrivate())
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
