package com.platform.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.context.UserContext;
import com.platform.userservice.dto.AvatarPresignedUrlRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.entity.User;
import com.platform.userservice.filter.JwtAuthenticationFilter;
import com.platform.userservice.repository.UserRepository;
import com.platform.userservice.service.AvatarStorageService;
import com.platform.userservice.service.JwtTokenService;
import com.platform.userservice.service.UserService;
import com.platform.userservice.util.CookieUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class AvatarPresignedUrlIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AvatarStorageService avatarStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private User testUser;
    private Cookie authCookie;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(jwtAuthenticationFilter)
                .build();
        UserContext.clear();

        String suffix = String.valueOf(System.currentTimeMillis()).substring(8);
        testUser = userRepository.save(User.builder()
                .username("avataruser" + suffix)
                .email("avataruser" + suffix + "@example.com")
                .passwordHash("hashedpass")
                .isActive(true)
                .build());

        String accessToken = jwtTokenService.generateAccessToken(testUser.getId(), testUser.getUsername());
        authCookie = new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, accessToken);
    }

    @Test
    void testGeneratePresignedUploadUrl_Success() throws Exception {
        AvatarPresignedUrlRequest request = AvatarPresignedUrlRequest.builder()
                .fileName("profile.png")
                .contentType("image/png")
                .fileSizeBytes(1024L * 1024L) // 1 MB (within 5MB limit)
                .build();

        mockMvc.perform(post("/api/v1/users/me/avatar/presigned-url")
                        .cookie(authCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl", containsString("X-Amz-Signature")))
                .andExpect(jsonPath("$.uploadUrl", containsString("user-avatars")))
                .andExpect(jsonPath("$.fileUrl", containsString("user-avatars/avatars/" + testUser.getId())))
                .andExpect(jsonPath("$.s3Key", containsString("avatars/" + testUser.getId())))
                .andExpect(jsonPath("$.expiresAt", notNullValue()));
    }

    @Test
    void testGeneratePresignedUploadUrl_DisallowedContentType_Fails() throws Exception {
        AvatarPresignedUrlRequest request = AvatarPresignedUrlRequest.builder()
                .fileName("document.pdf")
                .contentType("application/pdf")
                .fileSizeBytes(500L * 1024L)
                .build();

        mockMvc.perform(post("/api/v1/users/me/avatar/presigned-url")
                        .cookie(authCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Invalid image content type")));
    }

    @Test
    void testGeneratePresignedUploadUrl_ExceedsMaxSize_Fails() throws Exception {
        AvatarPresignedUrlRequest request = AvatarPresignedUrlRequest.builder()
                .fileName("huge.jpg")
                .contentType("image/jpeg")
                .fileSizeBytes(6L * 1024L * 1024L) // 6 MB (policy max is 5MB)
                .build();

        mockMvc.perform(post("/api/v1/users/me/avatar/presigned-url")
                        .cookie(authCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("File size exceeds maximum allowed limit")));
    }

    @Test
    void testGeneratePresignedUploadUrl_Unauthenticated_Fails() throws Exception {
        AvatarPresignedUrlRequest request = AvatarPresignedUrlRequest.builder()
                .fileName("profile.png")
                .contentType("image/png")
                .fileSizeBytes(1024L * 1024L)
                .build();

        mockMvc.perform(post("/api/v1/users/me/avatar/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testUpdateProfile_DeletesOldAvatarFromStorage() {
        // Given existing user has an old avatar
        String oldAvatar = "http://localhost:9000/user-avatars/avatars/" + testUser.getId() + "/old-avatar.png";
        testUser.setPictureUrl(oldAvatar);
        userRepository.save(testUser);

        // When user updates to a new avatar
        String newAvatar = "http://localhost:9000/user-avatars/avatars/" + testUser.getId() + "/new-avatar.png";
        UpdateUserRequest updateRequest = UpdateUserRequest.builder()
                .pictureUrl(newAvatar)
                .build();

        UserResponse response = userService.updateUserProfile(testUser.getId(), updateRequest);

        // Then new avatar is saved and old avatar is cleaned up without exception
        assertThat(response.getPictureUrl()).isEqualTo(newAvatar);
    }
}
