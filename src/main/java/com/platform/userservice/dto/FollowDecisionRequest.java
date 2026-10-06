package com.platform.userservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowDecisionRequest {

    @NotNull(message = "Follower ID is required")
    private UUID followerId;

    @NotNull(message = "Accept decision is required")
    private Boolean accept;
}
