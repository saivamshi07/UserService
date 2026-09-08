package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserDisableConfig {
    private boolean enabled = true;
    private boolean allowSelfReactivation = true;
}