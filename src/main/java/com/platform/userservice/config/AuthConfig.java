package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AuthConfig {

    private AuthMode mode = AuthMode.EITHER;
    private boolean allowGoogle = true;
    private boolean requireEmailOtp = true;
    private boolean requirePhoneOtp = true;

}