package com.platform.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.context.UserContext;
import com.platform.userservice.dto.BatchAccessContextRequest;
import com.platform.userservice.dto.BatchAccessContextResponse;
import com.platform.userservice.dto.FollowDecisionRequest;
import com.platform.userservice.dto.FollowStatusResponse;
import com.platform.userservice.dto.FollowUserRequest;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserAccessCheckResponse;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.entity.FollowStatus;
import com.platform.userservice.repository.UserFollowRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private UserFollowRepository userFollowRepository;

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
    void testFollowWorkflowUpdatesGraphRedisSynchronously() {
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
        UserResponse requester = registerTestUser("requester", false);
        UserResponse privateTarget = registerTestUser("privtarget", true);

        // 1. Follow private user (PENDING status)
        userFollowService.followUser(requester.getId(), privateTarget.getId());

        // Redis must NOT contain following link yet
        Boolean isFollowing = userGraphRedisService.isFollowing(requester.getId(), privateTarget.getId());
        assertFalse(Boolean.TRUE.equals(isFollowing), "Redis must not have follow link while PENDING");

        // 2. Target accepts follow request
        userFollowService.respondToFollowRequest(privateTarget.getId(), requester.getId(), true);

        // Redis MUST now contain following link
        isFollowing = userGraphRedisService.isFollowing(requester.getId(), privateTarget.getId());
        assertTrue(Boolean.TRUE.equals(isFollowing), "Redis must contain follow link after ACCEPTED");

        // 3. Check access check endpoint
        UserAccessCheckResponse access = userFollowService.checkAccess(privateTarget.getId(), requester.getId());
        assertTrue(access.isCanView(), "Follower should now be authorized to view private author content");
        assertTrue(access.isFollowing());
    }

    @Test
    void testAccessCheckEndpointsAndSelfBypass() {
        UserResponse owner = registerTestUser("owner", true);
        UserResponse stranger = registerTestUser("stranger", false);

        // 1. Self bypass: owner viewing own private profile
        UserAccessCheckResponse selfAccess = userFollowService.checkAccess(owner.getId(), owner.getId());
        assertTrue(selfAccess.isCanView(), "Owner should always have access to view own content");
        assertTrue(selfAccess.isFollowing());

        // 2. Stranger viewing private profile without following
        UserAccessCheckResponse strangerAccess = userFollowService.checkAccess(owner.getId(), stranger.getId());
        assertFalse(strangerAccess.isCanView(), "Stranger without accepted follow must be denied");
        assertFalse(strangerAccess.isFollowing());

        // 3. Anonymous/guest viewer viewing private profile
        UserAccessCheckResponse guestAccess = userFollowService.checkAccess(owner.getId(), null);
        assertFalse(guestAccess.isCanView(), "Guest viewer must be denied access to private profile");
    }

    @Test
    void testFollowStatusEndpoint() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        UserResponse userA = registerTestUser("usera", false);
        UserResponse userB = registerTestUser("userb", false);

        UserContext.setUserId(userA.getId());

        // Initially not following
        mockMvc.perform(get("/api/v1/users/" + userB.getId() + "/follow-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFollowing").value(false));

        // Follow user B
        userFollowService.followUser(userA.getId(), userB.getId());

        // Now following
        mockMvc.perform(get("/api/v1/users/" + userB.getId() + "/follow-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFollowing").value(true))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void testPendingAndSentFollowRequestsEndpoints() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        UserResponse target = registerTestUser("targetuser", true);
        UserResponse req1 = registerTestUser("reqone", false);
        UserResponse req2 = registerTestUser("reqtwo", false);

        // 1. Req1 and Req2 request to follow private target
        userFollowService.followUser(req1.getId(), target.getId());
        userFollowService.followUser(req2.getId(), target.getId());

        // 2. Target checks pending incoming requests
        UserContext.setUserId(target.getId());
        mockMvc.perform(get("/api/v1/users/follows/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/v1/users/me/follow-requests/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // Check Req1 sent requests
        UserContext.setUserId(req1.getId());
        mockMvc.perform(get("/api/v1/users/me/follow-requests/sent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/users/follows/sent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // 3. Target accepts req1's request
        UserContext.setUserId(target.getId());
        userFollowService.respondToFollowRequest(target.getId(), req1.getId(), true);

        // Target pending requests should now be 1 (only req2 left)
        mockMvc.perform(get("/api/v1/users/follows/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].followerId").value(req2.getId().toString()));

        // Req1 sent requests should now be 0 (no longer pending)
        UserContext.setUserId(req1.getId());
        mockMvc.perform(get("/api/v1/users/me/follow-requests/sent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void testRemoveFollowerEndpoint() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        UserResponse target = registerTestUser("target", false);
        UserResponse follower = registerTestUser("follower", false);

        // Follow target user (immediately accepted since target is public)
        userFollowService.followUser(follower.getId(), target.getId());

        // Verify follower is following target
        Boolean isFollowingBefore = userGraphRedisService.isFollowing(follower.getId(), target.getId());
        assertTrue(Boolean.TRUE.equals(isFollowingBefore));
        assertTrue(userFollowRepository.findByIdFollowerIdAndIdFollowingId(follower.getId(), target.getId()).isPresent());

        // Target removes follower using DELETE /api/v1/users/followers/{followerId}
        UserContext.setUserId(target.getId());
        mockMvc.perform(delete("/api/v1/users/followers/" + follower.getId()))
                .andExpect(status().isNoContent());

        // Verify relationship is deleted from Redis graph cache
        Boolean isFollowingAfter = userGraphRedisService.isFollowing(follower.getId(), target.getId());
        assertFalse(Boolean.TRUE.equals(isFollowingAfter));

        // Verify relationship is deleted from PostgreSQL database
        assertFalse(userFollowRepository.findByIdFollowerIdAndIdFollowingId(follower.getId(), target.getId()).isPresent());

        // Test alias endpoint DELETE /api/v1/users/me/followers/{followerId}
        userFollowService.followUser(follower.getId(), target.getId());
        assertTrue(Boolean.TRUE.equals(userGraphRedisService.isFollowing(follower.getId(), target.getId())));

        mockMvc.perform(delete("/api/v1/users/me/followers/" + follower.getId()))
                .andExpect(status().isNoContent());

        assertFalse(Boolean.TRUE.equals(userGraphRedisService.isFollowing(follower.getId(), target.getId())));
        assertFalse(userFollowRepository.findByIdFollowerIdAndIdFollowingId(follower.getId(), target.getId()).isPresent());
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
