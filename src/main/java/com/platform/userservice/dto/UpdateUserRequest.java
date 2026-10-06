package com.platform.userservice.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {

    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    private String username;

    @Size(max = 160, message = "Bio cannot exceed 160 characters")
    private String bio;

    @Size(max = 500, message = "Picture URL cannot exceed 500 characters")
    @Pattern(
        regexp = "^$|^https?://.*",
        message = "Picture URL must be a valid HTTP or HTTPS URL"
    )
    private String pictureUrl;

    private Boolean isPrivate;

    private Boolean isEmailPrivate;

    private Boolean isPhonePrivate;

    private Boolean isPicturePrivate;

    @Size(max = 20, message = "Cannot have more than 20 public profiles or social links")
    private Map<String, Object> publicProfiles;
}
