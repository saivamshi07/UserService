package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NameConfig {
    private int maxLength = 50;
    private String regex = "^[a-zA-Z0-9 ]+$";
}