package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PublicProfilesConfig {
    private boolean enabled = true;
    private boolean allowVisibilityToggle = true;
}