package com.platform.userservice.dto;

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

    private String bio;
    private String pictureUrl;
    private Boolean isPrivate;
    private Boolean isEmailPrivate;
    private Boolean isPhonePrivate;
    private Boolean isPicturePrivate;
    private Map<String, Object> publicProfiles;
}
