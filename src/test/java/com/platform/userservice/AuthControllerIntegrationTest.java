package com.platform.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.dto.LoginRequest;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.util.CookieUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class AuthControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void testAuthCookiesLifecycleAndRedisRevocation() throws Exception {
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String username = "jwtuser" + uniqueSuffix.substring(uniqueSuffix.length() - 4);
        String email = username + "@example.com";
        String password = "StrongPassword123!";

        RegisterRequest registerRequest = RegisterRequest.builder()
                .username(username)
                .email(email)
                .password(password)
                .build();

        // 1. Register: verify HTTP 201 and Set-Cookie for access_token and refresh_token
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().httpOnly(CookieUtil.ACCESS_TOKEN_COOKIE, true))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andExpect(cookie().httpOnly(CookieUtil.REFRESH_TOKEN_COOKIE, true))
                .andExpect(jsonPath("$.username").value(username));

        // 2. Login: verify HTTP 200 and cookies
        LoginRequest loginRequest = LoginRequest.builder()
                .identifier(username)
                .password(password)
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertNotNull(refreshCookie);

        // 3. Refresh: call /refresh with valid refresh_token cookie (rotates session in Redis)
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();

        // The old refresh token should now be rotated and revoked in Redis
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized());

        // Get newly rotated refresh token
        Cookie rotatedRefreshCookie = refreshResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertNotNull(rotatedRefreshCookie);

        // 4. Logout: pass the active refresh token cookie so Redis revokes it
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(rotatedRefreshCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(CookieUtil.ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.REFRESH_TOKEN_COOKIE, 0));

        // 5. Attempt to use revoked refresh token after logout -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(rotatedRefreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token session is invalid or has been revoked."));
    }
}
