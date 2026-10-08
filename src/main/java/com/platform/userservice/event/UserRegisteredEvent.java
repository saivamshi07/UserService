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
public class UserRegisteredEvent {
    private UUID eventId;
    private UUID userId;
    private String username;
    private String email;
    private String phone;
    private Instant timestamp;
}
