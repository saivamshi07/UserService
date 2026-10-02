package com.platform.userservice;

import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.dto.UserSearchResponse;
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
}
