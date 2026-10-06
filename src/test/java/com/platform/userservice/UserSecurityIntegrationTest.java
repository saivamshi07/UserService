package com.platform.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.filter.JwtAuthenticationFilter;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class UserSecurityIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtTokenService jwtTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(jwtAuthenticationFilter)
                .build();
    }

    @Test
    void testProtectedEndpointRequiresAuthentication() throws Exception {
        UpdateUserRequest updateRequest = UpdateUserRequest.builder()
                .bio("Unauthenticated Bio")
                .build();

        // 1. Calling /me without any token or cookie -> 401 Unauthorized
        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testAttackerCannotSpoofXUserIdHeader() throws Exception {
        UUID victimId = UUID.randomUUID();
        UpdateUserRequest updateRequest = UpdateUserRequest.builder()
                .bio("Attacker Bio")
                .build();

        // 2. Attacker sends fake X-User-Id header without trusted secret or JWT -> 401 Unauthorized
        mockMvc.perform(put("/api/v1/users/me")
                        .header("X-User-Id", victimId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testAuthenticUserWithJwtCookieCanUpdateProfile() throws Exception {
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String username = "secuser" + uniqueSuffix.substring(uniqueSuffix.length() - 4);

        UserResponse user = userService.registerUser(RegisterRequest.builder()
                .username(username)
                .email(username + "@example.com")
                .password("StrongPassword123!")
                .build(), "127.0.0.1", "JUnit");

        String accessToken = jwtTokenService.generateAccessToken(user.getId(), user.getUsername());
        Cookie authCookie = new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, accessToken);

        UpdateUserRequest updateRequest = UpdateUserRequest.builder()
                .bio("Authentic Bio Update")
                .build();

        // 3. User sends valid access_token cookie -> 200 OK
        mockMvc.perform(put("/api/v1/users/me")
                        .cookie(authCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Authentic Bio Update"));
    }

    @Test
    void testAuthenticUserWithBearerHeaderCanUpdateProfile() throws Exception {
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String username = "bearer" + uniqueSuffix.substring(uniqueSuffix.length() - 4);

        UserResponse user = userService.registerUser(RegisterRequest.builder()
                .username(username)
                .email(username + "@example.com")
                .password("StrongPassword123!")
                .build(), "127.0.0.1", "JUnit");

        String accessToken = jwtTokenService.generateAccessToken(user.getId(), user.getUsername());

        UpdateUserRequest updateRequest = UpdateUserRequest.builder()
                .bio("Bearer Bio Update")
                .build();

        // 4. User sends Authorization: Bearer <token> -> 200 OK
        mockMvc.perform(put("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Bearer Bio Update"));
    }
}
