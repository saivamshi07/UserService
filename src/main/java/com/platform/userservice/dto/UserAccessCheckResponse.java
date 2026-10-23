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
public class UserAccessCheckResponse {

    private UUID authorId;
    private UUID viewerId;
    private boolean isPrivate;
    private boolean isFollowing;

    /**
     * Final evaluation: true if viewer is authorized to view author's reviews/ratings,
     * false if author is private and viewer does not follow them.
     */
    private boolean canView;
}
