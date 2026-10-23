package com.platform.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.context.UserContext;
import com.platform.userservice.dto.BatchAccessContextRequest;
import com.platform.userservice.dto.BatchAccessContextResponse;
import com.platform.userservice.dto.FollowDecisionRequest;
import com.platform.userservice.dto.FollowUserRequest;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserAccessCheckResponse;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.service.UserFollowService;
import com.platform.userservice.service.UserGraphRedisService;
import com.platform.userservice.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class UserAccessIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserService userService;

    @Autowired
    private UserFollowService userFollowService;

    @Autowired
    private UserGraphRedisService userGraphRedisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UserResponse registerTestUser(String username, boolean isPrivate) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = RegisterRequest.builder()
                .username(username + suffix)
                .email(username + suffix + "@test.com")
                .password("Password123!")
                .build();
        UserResponse response = userService.registerUser(request, "127.0.0.1", "TestAgent");
        if (isPrivate) {
            userService.updateUserProfile(response.getId(), UpdateUserRequest.builder().isPrivate(true).build());
            response.setPrivate(true);
        }
        return response;
    }

    @Test
    void testFollowAndUnfollowUpdatesDedicatedRedis() {
        UserResponse follower = registerTestUser("follower", false);
        UserResponse publicTarget = registerTestUser("pubtarget", false);

        // 1. Follow public user (immediately ACCEPTED)
        userFollowService.followUser(follower.getId(), publicTarget.getId());

        // Verify Redis has the follow link
        Boolean isFollowing = userGraphRedisService.isFollowing(follower.getId(), publicTarget.getId());
        assertTrue(Boolean.TRUE.equals(isFollowing), "Redis should contain follow link immediately for public user");

        // 2. Unfollow user
        userFollowService.unfollowUser(follower.getId(), publicTarget.getId());

        // Verify Redis link is removed
        isFollowing = userGraphRedisService.isFollowing(follower.getId(), publicTarget.getId());
        assertFalse(Boolean.TRUE.equals(isFollowing), "Redis should no longer contain follow link after unfollow");
    }

    @Test
    void testPrivateUserFollowApprovalUpdatesRedis() {
        UserResponse follower = registerTestUser("reqfollower", false);
        UserResponse privateTarget = registerTestUser("privtarget", true);

        // 1. Request to follow private user (Status: PENDING)
        userFollowService.followUser(follower.getId(), privateTarget.getId());

        // Should NOT be in Redis yet while PENDING
        Boolean isFollowingPending = userGraphRedisService.isFollowing(follower.getId(), privateTarget.getId());
        assertFalse(Boolean.TRUE.equals(isFollowingPending), "Pending follow request must not be stored as active following in Redis");

        // 2. Target user accepts follow request
        userFollowService.respondToFollowRequest(privateTarget.getId(), follower.getId(), true);

        // Now must be in Redis
        Boolean isFollowingAccepted = userGraphRedisService.isFollowing(follower.getId(), privateTarget.getId());
        assertTrue(Boolean.TRUE.equals(isFollowingAccepted), "Accepted follow request must now be in Redis");
    }

    @Test
    void testSingleAccessCheckEndpoint() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        UserResponse viewer = registerTestUser("viewer", false);
        UserResponse publicAuthor = registerTestUser("publicauthor", false);
        UserResponse privateAuthor = registerTestUser("privateauthor", true);

        // 1. Check access on public author -> canView: true
        mockMvc.perform(get("/api/v1/users/{id}/access-check", publicAuthor.getId())
                        .param("viewerId", viewer.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.private").value(false));

        // 2. Check access on private author without following -> canView: false
        mockMvc.perform(get("/api/v1/users/{id}/access-check", privateAuthor.getId())
                        .param("viewerId", viewer.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canView").value(false))
                .andExpect(jsonPath("$.private").value(true))
                .andExpect(jsonPath("$.following").value(false));

        // 3. Guest check on private author (no viewerId) -> canView: false
        mockMvc.perform(get("/api/v1/users/{id}/access-check", privateAuthor.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canView").value(false))
                .andExpect(jsonPath("$.private").value(true));

        // 4. Follow and accept -> canView: true
        userFollowService.followUser(viewer.getId(), privateAuthor.getId());
        userFollowService.respondToFollowRequest(privateAuthor.getId(), viewer.getId(), true);

        mockMvc.perform(get("/api/v1/users/{id}/access-check", privateAuthor.getId())
                        .param("viewerId", viewer.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.private").value(true))
                .andExpect(jsonPath("$.following").value(true));

        // 5. Self-view bypass -> canView: true
        mockMvc.perform(get("/api/v1/users/{id}/access-check", privateAuthor.getId())
                        .param("viewerId", privateAuthor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canView").value(true));
    }

    @Test
    void testBatchAccessContextEndpointAndWarming() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        UserResponse viewer = registerTestUser("batchviewer", false);
        UserResponse author1 = registerTestUser("batchauthor1", false); // public
        UserResponse author2 = registerTestUser("batchauthor2", true);  // private
        UserResponse author3 = registerTestUser("batchauthor3", true);  // private

        // Follow author 2 only
        userFollowService.followUser(viewer.getId(), author2.getId());
        userFollowService.respondToFollowRequest(author2.getId(), viewer.getId(), true);

        BatchAccessContextRequest request = BatchAccessContextRequest.builder()
                .viewerId(viewer.getId())
                .authorIds(List.of(author1.getId(), author2.getId(), author3.getId()))
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/users/access-context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        BatchAccessContextResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), BatchAccessContextResponse.class);

        assertNotNull(response);
        assertEquals(viewer.getId(), response.getViewerId());
        assertTrue(response.getViewerFollowing().contains(author2.getId()));
        assertFalse(response.getViewerFollowing().contains(author3.getId()));

        assertFalse(response.getAuthorPrivacy().get(author1.getId())); // public
        assertTrue(response.getAuthorPrivacy().get(author2.getId()));  // private
        assertTrue(response.getAuthorPrivacy().get(author3.getId()));  // private

        // Verify Redis was warmed with both privacy and following entries
        assertEquals(Boolean.FALSE, userGraphRedisService.getUserPrivacy(author1.getId()));
        assertEquals(Boolean.TRUE, userGraphRedisService.getUserPrivacy(author2.getId()));
        assertTrue(userGraphRedisService.getFollowing(viewer.getId()).contains(author2.getId()));
    }
}
