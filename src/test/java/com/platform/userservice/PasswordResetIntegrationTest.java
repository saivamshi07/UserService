package com.platform.userservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.userservice.context.UserContext;
import com.platform.userservice.dto.ChangePasswordRequest;
import com.platform.userservice.dto.ForgotPasswordRequest;
import com.platform.userservice.dto.LoginRequest;
import com.platform.userservice.dto.RegisterRequest;
import com.platform.userservice.dto.ResetPasswordRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.entity.OutboxEvent;
import com.platform.userservice.event.PasswordResetRequestedEvent;
import com.platform.userservice.repository.OutboxEventRepository;
import com.platform.userservice.service.RedisSessionService;
import com.platform.userservice.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class PasswordResetIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserService userService;

    @Autowired
    private RedisSessionService redisSessionService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UserResponse registerUser(String prefix, String initialPassword) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = RegisterRequest.builder()
                .username(prefix + suffix)
                .email(prefix + suffix + "@resettest.com")
                .password(initialPassword)
                .build();
        return userService.registerUser(request, "127.0.0.1", "TestAgent");
    }

    @Test
    void testForgotPasswordAndResetLifecycle() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        String oldPassword = "InitialPassword123!";
        String newPassword = "BrandNewPassword123!";
        UserResponse user = registerUser("pwdreset", oldPassword);

        // 1. Request password reset via HTTP API
        ForgotPasswordRequest forgotReq = ForgotPasswordRequest.builder()
                .identifier(user.getEmail())
                .build();

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forgotReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If an active account exists with that identifier, instructions to reset your password have been sent."))
                // CRITICAL SECURITY ASSERTION: resetToken must NEVER leak in HTTP response
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        // 2. Production out-of-band delivery verification:
        // Token is dispatched via Transactional Outbox (for mailer worker) and stored in Redis
        String resetToken = redisSessionService.getActivePasswordResetToken(user.getId());
        assertNotNull(resetToken, "Reset token must be stored securely in Redis");

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> "PASSWORD_RESET_REQUESTED".equals(e.getEventType()) && user.getId().toString().equals(e.getAggregateId()))
                .toList();
        assertThat(events).isNotEmpty();
        PasswordResetRequestedEvent outboxPayload = objectMapper.readValue(events.getLast().getPayload(), PasswordResetRequestedEvent.class);
        assertThat(outboxPayload.getResetToken()).isEqualTo(resetToken);
        assertThat(outboxPayload.getEmail()).isEqualTo(user.getEmail());

        // 3. Perform password reset with received token
        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .resetToken(resetToken)
                .newPassword(newPassword)
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 4. Verify old password no longer works
        LoginRequest oldLoginReq = LoginRequest.builder()
                .identifier(user.getUsername())
                .password(oldPassword)
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oldLoginReq)))
                .andExpect(status().isUnauthorized());

        // 5. Verify new password logs in successfully
        LoginRequest newLoginReq = LoginRequest.builder()
                .identifier(user.getUsername())
                .password(newPassword)
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newLoginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()));

        // 6. Verify token is single-use and cannot be replayed
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testAttackerTriesForgotPasswordWithVictimId_NeverExposesTokenAndAppliesCooldown() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        String victimPassword = "VictimOriginalPass123!";
        UserResponse victim = registerUser("victim", victimPassword);

        // Attacker attempts to hit forgot-password using the victim's username
        ForgotPasswordRequest attackerReq = ForgotPasswordRequest.builder()
                .identifier(victim.getUsername())
                .build();

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attackerReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If an active account exists with that identifier, instructions to reset your password have been sent."))
                // Attacker receives NO token in HTTP response body
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        // Attacker attempts unauthorized takeover with a fabricated token -> 401 Unauthorized
        ResetPasswordRequest takeoverReq = ResetPasswordRequest.builder()
                .resetToken(UUID.randomUUID().toString())
                .newPassword("AttackerTakeoverPass123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(takeoverReq)))
                .andExpect(status().isUnauthorized());

        // Attacker attempts rapid spamming / inbox-bombing against victim ID
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attackerReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        // Cooldown suppresses duplicate token generation and duplicate outbox event
        long resetEventCount = outboxEventRepository.findAll().stream()
                .filter(e -> "PASSWORD_RESET_REQUESTED".equals(e.getEventType()) && victim.getId().toString().equals(e.getAggregateId()))
                .count();
        assertThat(resetEventCount).isEqualTo(1);

        // Victim's original account credentials and password remain completely intact
        LoginRequest victimLogin = LoginRequest.builder()
                .identifier(victim.getUsername())
                .password(victimPassword)
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(victimLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(victim.getId().toString()));
    }

    @Test
    void testResetPasswordRejectsSamePassword() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        String password = "SamePassword123!";
        UserResponse user = registerUser("pwsame", password);

        ForgotPasswordRequest forgotReq = ForgotPasswordRequest.builder()
                .identifier(user.getUsername())
                .build();

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forgotReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        String resetToken = redisSessionService.getActivePasswordResetToken(user.getId());
        assertNotNull(resetToken);

        ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                .resetToken(resetToken)
                .newPassword(password)
                .build();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testAuthenticatedChangePassword() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        String currentPassword = "CurrentPassword123!";
        String changedPassword = "ChangedPassword123!";
        UserResponse user = registerUser("pwchange", currentPassword);

        // Simulate authenticated user context
        UserContext.setUserId(user.getId());

        // 1. Wrong current password -> 400
        ChangePasswordRequest wrongCurrentReq = ChangePasswordRequest.builder()
                .currentPassword("WrongPassword!")
                .newPassword(changedPassword)
                .build();

        mockMvc.perform(put("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongCurrentReq)))
                .andExpect(status().isBadRequest());

        // 2. Correct current password -> 200 OK
        ChangePasswordRequest validReq = ChangePasswordRequest.builder()
                .currentPassword(currentPassword)
                .newPassword(changedPassword)
                .build();

        mockMvc.perform(put("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        // 3. Login with newly changed password
        LoginRequest loginReq = LoginRequest.builder()
                .identifier(user.getUsername())
                .password(changedPassword)
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()));
    }
}
