package com.platform.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private UUID id;
    private String username;
    private String email;
    private String phone;
    private String bio;
    private String pictureUrl;
    private boolean isPrivate;
    private boolean isEmailVerified;
    private boolean isPhoneVerified;
    private Map<String, Object> publicProfiles;
    private long followersCount;
    private long followingCount;
    private Instant createdAt;
    private Instant updatedAt;
}
