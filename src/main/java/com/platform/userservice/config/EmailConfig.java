package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailConfig {
    private boolean mandatory = true;
    private boolean allowVisibilityToggle = true;
}