package com.platform.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchAccessContextResponse {

    /**
     * The ID of the viewing user (or null if guest).
     */
    private UUID viewerId;

    /**
     * The set of user IDs that the viewer is currently following (with ACCEPTED status).
     * Empty if viewer is null or follows no one.
     */
    private Set<UUID> viewerFollowing;

    /**
     * Map of authorId -> isPrivate flag.
     * True means private account, False means public.
     */
    private Map<UUID, Boolean> authorPrivacy;
}
