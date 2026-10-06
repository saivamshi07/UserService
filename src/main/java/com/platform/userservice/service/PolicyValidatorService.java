package com.platform.userservice.service;

import com.platform.userservice.config.AuthMode;
import com.platform.userservice.config.UserPolicyConfig;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.exception.PolicyViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyValidatorService {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );

    private final UserPolicyConfig policy;

    public void validateRegistration(RegisterRequest request) {
        validateUsername(request.getUsername());
        validateAuthMode(request.getEmail(), request.getPhone());
    }

    public void validateUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new PolicyViolationException("Username cannot be empty");
        }
        if (username.length() > policy.getName().getMaxLength()) {
            throw new PolicyViolationException("Username exceeds maximum allowed length of " + policy.getName().getMaxLength());
        }
        if (StringUtils.hasText(policy.getName().getRegex())) {
            if (!Pattern.matches(policy.getName().getRegex(), username)) {
                throw new PolicyViolationException("Username contains invalid characters. Allowed format: " + policy.getName().getRegex());
            }
        }
    }

    public void validateAuthMode(String email, String phone) {
        boolean hasEmail = StringUtils.hasText(email);
        boolean hasPhone = StringUtils.hasText(phone);

        if (policy.getEmail().isMandatory() && !hasEmail) {
            throw new PolicyViolationException("Email is mandatory according to current system policy");
        }
        if (policy.getPhone().isMandatory() && !hasPhone) {
            throw new PolicyViolationException("Phone is mandatory according to current system policy");
        }

        AuthMode mode = policy.getAuth().getMode();
        switch (mode) {
            case ONLY_EMAIL -> {
                if (!hasEmail) {
                    throw new PolicyViolationException("Registration policy requires an email address");
                }
                if (hasPhone) {
                    throw new PolicyViolationException("Registration with phone is not permitted under ONLY_EMAIL mode");
                }
            }
            case ONLY_PHONE -> {
                if (!hasPhone) {
                    throw new PolicyViolationException("Registration policy requires a phone number");
                }
                if (hasEmail) {
                    throw new PolicyViolationException("Registration with email is not permitted under ONLY_PHONE mode");
                }
            }
            case BOTH -> {
                if (!hasEmail || !hasPhone) {
                    throw new PolicyViolationException("Both email and phone number are required under BOTH policy mode");
                }
            }
            case EITHER -> {
                if (!hasEmail && !hasPhone) {
                    throw new PolicyViolationException("Either email or phone number must be provided");
                }
            }
        }
    }

    public void validateUpdateProfile(UpdateUserRequest request) {
        if (request.getUsername() != null) {
            validateUsername(request.getUsername());
        }

        if (request.getBio() != null) {
            if (!policy.getBio().isEnabled() && StringUtils.hasText(request.getBio())) {
                throw new PolicyViolationException("Bio feature is disabled by system policy");
            }
            if (request.getBio().length() > policy.getBio().getMaxCharacters()) {
                throw new PolicyViolationException("Bio exceeds maximum allowed length of " + policy.getBio().getMaxCharacters());
            }
        }

        if (request.getPictureUrl() != null && !policy.getPicture().isEnabled()) {
            throw new PolicyViolationException("Profile picture feature is disabled by system policy");
        }

        if (request.getIsPrivate() != null && !policy.getPrivacy().isEnabled()) {
            throw new PolicyViolationException("Profile privacy toggle is disabled by system policy");
        }

        if (request.getIsEmailPrivate() != null && !policy.getEmail().isAllowVisibilityToggle()) {
            throw new PolicyViolationException("Email visibility toggle is disabled by system policy");
        }

        if (request.getIsPhonePrivate() != null && !policy.getPhone().isAllowVisibilityToggle()) {
            throw new PolicyViolationException("Phone visibility toggle is disabled by system policy");
        }

        if (request.getIsPicturePrivate() != null && !policy.getPicture().isAllowPrivateToggle()) {
            throw new PolicyViolationException("Picture visibility toggle is disabled by system policy");
        }

        if (request.getPublicProfiles() != null && !policy.getPublicProfiles().isEnabled()) {
            throw new PolicyViolationException("Public profiles / social links are disabled by system policy");
        }
    }

    public void validateAvatarUpload(String contentType, long fileSizeBytes) {
        if (!policy.getPicture().isEnabled()) {
            throw new PolicyViolationException("Profile picture upload feature is disabled by system policy");
        }

        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new PolicyViolationException("Invalid image content type: " + contentType + ". Allowed types: JPEG, PNG, WEBP");
        }

        long maxSizeBytes = policy.getPicture().getMaxSizeMb() * 1024L * 1024L;
        if (fileSizeBytes > maxSizeBytes) {
            throw new PolicyViolationException("File size exceeds maximum allowed limit of " + policy.getPicture().getMaxSizeMb() + " MB");
        }
    }

    public void validateGoogleAuthAllowed() {
        if (!policy.getAuth().isAllowGoogle()) {
            throw new PolicyViolationException("Google authentication is currently disabled by system policy");
        }
    }

    public void validateFollowersAllowed() {
        if (!policy.getFollowers().isEnabled()) {
            throw new PolicyViolationException("Followers system is currently disabled by system policy");
        }
    }

    public void validateUserDisableAllowed() {
        if (!policy.getUserDisable().isEnabled()) {
            throw new PolicyViolationException("Account deactivation is currently disabled by system policy");
        }
    }
}
