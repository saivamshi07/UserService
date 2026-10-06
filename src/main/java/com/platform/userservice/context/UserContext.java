package com.platform.userservice.context;

import com.platform.userservice.exception.InvalidTokenException;

import java.util.UUID;

public final class UserContext {

    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(UUID userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static UUID getUserId() {
        return CURRENT_USER_ID.get();
    }

    public static UUID getRequiredUserId() {
        UUID userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new InvalidTokenException("Authentication required. Please log in.");
        }
        return userId;
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
