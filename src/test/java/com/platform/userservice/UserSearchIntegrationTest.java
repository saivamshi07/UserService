package com.platform.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.dto.BatchUsersRequest;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class UserSearchIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void testBatchUsersEndpoints() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        String suffix = String.valueOf(System.currentTimeMillis()).substring(8);
        UserResponse u1 = userService.registerUser(RegisterRequest.builder()
                .username("batchu1" + suffix)
                .email("batchu1" + suffix + "@test.com")
                .password("Password123!")
                .build(), "127.0.0.1", "JUnit");

        UserResponse u2 = userService.registerUser(RegisterRequest.builder()
                .username("batchu2" + suffix)
                .email("batchu2" + suffix + "@test.com")
                .password("Password123!")
                .build(), "127.0.0.1", "JUnit");

        List<UUID> ids = List.of(u1.getId(), u2.getId());

        // 1. Service direct lookup
        List<UserResponse> directList = userService.getUsersByIds(ids, null);
        assertEquals(2, directList.size());

        // 2. POST /api/v1/users/batch
        BatchUsersRequest batchRequest = BatchUsersRequest.builder()
                .ids(ids)
                .build();

        mockMvc.perform(post("/api/v1/users/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == '" + u1.getId() + "')].username").value(u1.getUsername()))
                .andExpect(jsonPath("$[?(@.id == '" + u2.getId() + "')].username").value(u2.getUsername()));

        // 3. GET /api/v1/users/batch?ids=...
        mockMvc.perform(get("/api/v1/users/batch")
                        .param("ids", u1.getId().toString(), u2.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // 4. GET /api/v1/users?ids=...
        mockMvc.perform(get("/api/v1/users")
                        .param("ids", u1.getId().toString(), u2.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
