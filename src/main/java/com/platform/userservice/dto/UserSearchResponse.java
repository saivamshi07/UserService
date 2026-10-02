package com.platform.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSearchResponse {

    private UUID id;
    private String username;
    private String pictureUrl;
    private String bio;
    private boolean isPrivate;
    private long followersCount;
}
