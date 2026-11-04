package com.platform.userservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.userservice.entity.FollowStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowStatusResponse {

    private UUID currentUserId;
    private UUID targetUserId;

    @JsonProperty("isFollowing")
    private boolean isFollowing;

    private FollowStatus status;
}
