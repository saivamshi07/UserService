package com.platform.userservice.dto;

import com.platform.userservice.entity.FollowStatus;
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
public class UserFollowResponse {

    private UUID followerId;
    private UUID followingId;
    private String username;
    private String pictureUrl;
    private FollowStatus status;
    private Instant createdAt;
}
