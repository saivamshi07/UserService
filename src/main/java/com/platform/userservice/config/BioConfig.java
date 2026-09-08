package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BioConfig {
    private boolean enabled = true;
    private int maxCharacters = 160;
}