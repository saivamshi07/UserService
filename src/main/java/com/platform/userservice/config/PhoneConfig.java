package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PhoneConfig {
    private boolean mandatory = false;
    private boolean allowVisibilityToggle = true;
}