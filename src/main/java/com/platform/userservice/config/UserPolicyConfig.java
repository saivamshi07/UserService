package com.platform.userservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "user-policy")
public class UserPolicyConfig {
    private AuthConfig auth = new AuthConfig();
    private NameConfig name = new NameConfig();
    private PictureConfig picture = new PictureConfig();
    private BioConfig bio = new BioConfig();
    private FollowersConfig followers = new FollowersConfig();
    private PrivacyConfig privacy = new PrivacyConfig();
    private EmailConfig email = new EmailConfig();
    private PhoneConfig phone = new PhoneConfig();
    private PublicProfilesConfig publicProfiles = new PublicProfilesConfig();
    private UserDisableConfig userDisable = new UserDisableConfig();
}