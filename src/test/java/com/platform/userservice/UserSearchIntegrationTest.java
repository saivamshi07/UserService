package com.platform.userservice;

import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.dto.UserSearchResponse;
import com.platform.userservice.exception.DuplicateResourceException;
import com.platform.userservice.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UserSearchIntegrationTest {

    @Autowired
    private UserService userService;

    @Test
    void testRegistrationAndTrigramSearch() {
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String username = "saivamshi" + uniqueSuffix.substring(uniqueSuffix.length() - 5);

        RegisterRequest request = RegisterRequest.builder()
                .username(username)
                .email(username + "@example.com")
                .password("Password123!")
                .build();

        UserResponse registered = userService.registerUser(request, "127.0.0.1", "JUnit-Test");
        assertNotNull(registered);
        assertEquals(username, registered.getUsername());

        // Perform pg_trgm fuzzy/prefix search
        Page<UserSearchResponse> searchResult = userService.searchUsers(username.substring(0, 5), PageRequest.of(0, 10));
        assertFalse(searchResult.isEmpty());
        assertTrue(searchResult.getContent().stream().anyMatch(u -> u.getUsername().equals(username)));
    }

    @Test
    void testUpdateUsernameAndCollision() {
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String userA = "usera" + uniqueSuffix.substring(uniqueSuffix.length() - 4);
        String userB = "userb" + uniqueSuffix.substring(uniqueSuffix.length() - 4);

        UserResponse uA = userService.registerUser(RegisterRequest.builder()
                .username(userA)
                .email(userA + "@test.com")
                .password("Password123!")
                .build(), "127.0.0.1", "JUnit");

        userService.registerUser(RegisterRequest.builder()
                .username(userB)
                .email(userB + "@test.com")
                .password("Password123!")
                .build(), "127.0.0.1", "JUnit");

        // Attempt to rename userA to userB -> should throw DuplicateResourceException
        assertThrows(DuplicateResourceException.class, () -> {
            userService.updateUserProfile(uA.getId(), UpdateUserRequest.builder()
                    .username(userB)
                    .build());
        });

        // Valid username update
        String newUsername = userA + "new";
        UserResponse updated = userService.updateUserProfile(uA.getId(), UpdateUserRequest.builder()
                .username(newUsername)
                .bio("Updated bio")
                .build());

        assertEquals(newUsername, updated.getUsername());
        assertEquals("Updated bio", updated.getBio());
    }
}
