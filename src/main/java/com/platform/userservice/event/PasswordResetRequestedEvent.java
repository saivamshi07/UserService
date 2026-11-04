package com.platform.userservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetRequestedEvent {
    private UUID eventId;
    private UUID userId;
    private String username;
    private String email;
    private String resetToken;
    private Instant expiresAt;
    private Instant timestamp;
}
