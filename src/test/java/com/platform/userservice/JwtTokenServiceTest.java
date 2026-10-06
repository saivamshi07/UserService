package com.platform.userservice;

import com.platform.userservice.service.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class JwtTokenServiceTest {

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void testAccessTokenLifecycle() {
        UUID userId = UUID.randomUUID();
        String username = "alice_wonder";

        String token = jwtTokenService.generateAccessToken(userId, username);
        assertNotNull(token);
        assertTrue(jwtTokenService.validateToken(token));

        assertEquals(userId, jwtTokenService.extractUserId(token));
        assertEquals(username, jwtTokenService.extractUsername(token));
    }

    @Test
    void testRefreshTokenLifecycle() {
        UUID userId = UUID.randomUUID();

        String refreshToken = jwtTokenService.generateRefreshToken(userId);
        assertNotNull(refreshToken);
        assertTrue(jwtTokenService.validateToken(refreshToken));
        assertTrue(jwtTokenService.validateRefreshToken(refreshToken));

        assertEquals(userId, jwtTokenService.extractUserId(refreshToken));
    }

    @Test
    void testInvalidToken() {
        assertFalse(jwtTokenService.validateToken("invalid.token.structure"));
        assertFalse(jwtTokenService.validateRefreshToken("invalid.token.structure"));
    }
}
