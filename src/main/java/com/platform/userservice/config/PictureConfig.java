package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PictureConfig {
    private boolean enabled = true;
    private boolean mandatory = false;
    private int maxSizeMb = 5;
    private boolean allowPrivateToggle = true;
}