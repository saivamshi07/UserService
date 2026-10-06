package com.platform.userservice.controller;

import com.platform.userservice.context.UserContext;
import com.platform.userservice.dto.AvatarPresignedUrlRequest;
import com.platform.userservice.dto.AvatarPresignedUrlResponse;
import com.platform.userservice.dto.UpdateUserRequest;
import com.platform.userservice.dto.UserResponse;
import com.platform.userservice.dto.UserSearchResponse;
import com.platform.userservice.service.AvatarStorageService;
import com.platform.userservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AvatarStorageService avatarStorageService;

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable("id") UUID id) {
        UUID currentUserId = UserContext.getUserId();
        return ResponseEntity.ok(userService.getUserById(id, currentUserId));
    }

    @GetMapping("/by-username/{username}")
    public ResponseEntity<UserResponse> getUserByUsername(@PathVariable("username") String username) {
        UUID currentUserId = UserContext.getUserId();
        return ResponseEntity.ok(userService.getUserByUsername(username, currentUserId));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(@Valid @RequestBody UpdateUserRequest request) {
        UUID currentUserId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(userService.updateUserProfile(currentUserId, request));
    }

    @PostMapping("/me/avatar/presigned-url")
    public ResponseEntity<AvatarPresignedUrlResponse> generateAvatarPresignedUrl(
            @Valid @RequestBody AvatarPresignedUrlRequest request) {
        UUID currentUserId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(avatarStorageService.createPresignedUploadUrl(currentUserId, request));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deactivateAccount(HttpServletRequest httpRequest) {
        UUID currentUserId = UserContext.getRequiredUserId();
        String clientIp = httpRequest.getRemoteAddr();
        String userAgent = httpRequest.getHeader("User-Agent");
        userService.deactivateUser(currentUserId, clientIp, userAgent);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public ResponseEntity<Page<UserSearchResponse>> searchUsers(
            @RequestParam("q") String query,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userService.searchUsers(query, pageable));
    }
}
