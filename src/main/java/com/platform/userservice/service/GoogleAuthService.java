package com.platform.userservice.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.platform.userservice.entity.User;
import com.platform.userservice.entity.UserAuditLog;
import com.platform.userservice.exception.InvalidTokenException;
import com.platform.userservice.repository.UserAuditLogRepository;
import com.platform.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final UserRepository userRepository;
    private final UserAuditLogRepository auditLogRepository;
    private final PolicyValidatorService policyValidator;

    @Value("${google.client-id:}")
    private String googleClientId;

    /**
     * Verifies the cryptographic authenticity of the Google ID Token.
     */
    public GoogleIdToken.Payload verifyToken(String idTokenString) {
        policyValidator.validateGoogleAuthAllowed();

        try {
            GoogleIdTokenVerifier.Builder verifierBuilder = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance()
            );

            if (StringUtils.hasText(googleClientId)) {
                verifierBuilder.setAudience(Collections.singletonList(googleClientId));
            }

            GoogleIdTokenVerifier verifier = verifierBuilder.build();
            GoogleIdToken idToken = verifier.verify(idTokenString);

            if (idToken == null) {
                throw new InvalidTokenException("Invalid or expired Google ID Token");
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            Boolean emailVerified = payload.getEmailVerified();
            if (emailVerified == null || !emailVerified) {
                throw new InvalidTokenException("Google account email is not verified");
            }

            return payload;
        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google ID Token verification failed: {}", e.getMessage());
            throw new InvalidTokenException("Failed to verify Google token: " + e.getMessage());
        }
    }

    /**
     * Finds or auto-provisions a user based on verified Google ID token payload.
     */
    @Transactional
    public User authenticateOrProvisionUser(String idTokenString, String clientIp, String userAgent) {
        GoogleIdToken.Payload payload = verifyToken(idTokenString);
        String email = payload.getEmail();
        String picture = (String) payload.get("picture");
        String name = (String) payload.get("name");

        Optional<User> existingUserOpt = userRepository.findByEmail(email);

        User user;
        String activity;

        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            activity = "GOOGLE_LOGIN";

            // If picture updated on Google, sync it
            if (StringUtils.hasText(picture) && !StringUtils.hasText(user.getPictureUrl())) {
                user.setPictureUrl(picture);
            }

            if (!user.isActive()) {
                user.setActive(true);
                user.setDisabledAt(null);
                log.info("Reactivated previously disabled user account via Google Login: {}", user.getId());
            }
        } else {
            activity = "GOOGLE_REGISTRATION";
            String generatedUsername = generateUniqueUsername(email, name);

            user = User.builder()
                    .username(generatedUsername)
                    .email(email)
                    .isEmailVerified(true)
                    .pictureUrl(picture)
                    .isActive(true)
                    .build();

            user = userRepository.save(user);
            log.info("Auto-provisioned new user account via Google Auth: {}", user.getId());
        }

        // Record Audit Log
        UserAuditLog auditLog = UserAuditLog.builder()
                .userId(user.getId())
                .activity(activity)
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .details(Map.of(
                        "email", email,
                        "authProvider", "GOOGLE",
                        "timestamp", Instant.now().toString()
                ))
                .build();
        auditLogRepository.save(auditLog);

        return user;
    }

    private String generateUniqueUsername(String email, String name) {
        String base = "";
        if (StringUtils.hasText(name)) {
            base = name.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        }
        if (!StringUtils.hasText(base) && StringUtils.hasText(email)) {
            base = email.split("@")[0].replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        }
        if (!StringUtils.hasText(base)) {
            base = "user";
        }

        if (base.length() > 30) {
            base = base.substring(0, 30);
        }

        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }
}
