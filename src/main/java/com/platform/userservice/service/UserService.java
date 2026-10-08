package com.platform.userservice.service;

import com.platform.userservice.dto.LoginRequest;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.dto.UserSearchResponse;
import com.platform.userservice.entity.FollowStatus;
import com.platform.userservice.entity.User;
import com.platform.userservice.entity.UserAuditLog;
import com.platform.userservice.event.UserDeactivatedEvent;
import com.platform.userservice.event.UserRegisteredEvent;
import com.platform.userservice.exception.DuplicateResourceException;
import com.platform.userservice.exception.InvalidTokenException;
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

import java.time.Instant;
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

    @Value("${kafka.topics.user-registered:user.registered.v1}")
    private String userRegisteredTopic;

    @Value("${kafka.topics.user-deactivated:user.deactivated.v1}")
    private String userDeactivatedTopic;

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
