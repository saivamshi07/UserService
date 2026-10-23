package com.platform.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchAccessContextRequest {

    /**
     * The ID of the viewing user. Can be null for unauthenticated / guest viewers.
     */
    private UUID viewerId;

    /**
     * The list of author IDs (e.g. review authors on a movie) to inspect.
     */
    private List<UUID> authorIds;
}
