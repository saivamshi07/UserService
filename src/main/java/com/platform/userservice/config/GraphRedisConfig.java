package com.platform.userservice.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Configuration
public class GraphRedisConfig {

    @Value("${graph.redis.host:${spring.data.redis.host:localhost}}")
    private String host;

    @Value("${graph.redis.port:${spring.data.redis.port:6379}}")
    private int port;

    @Value("${graph.redis.password:${spring.data.redis.password:}}")
    private String password;

    @Value("${graph.redis.database:1}")
    private int database;

    @Value("${graph.redis.timeout-ms:2000}")
    private long timeoutMs;

    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    @Bean(name = "graphRedisConnectionFactory")
    public LettuceConnectionFactory graphRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(database);
        if (StringUtils.hasText(password)) {
            config.setPassword(password);
        }

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeoutMs))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean(name = "graphRedisTemplate")
    public StringRedisTemplate graphRedisTemplate(@Qualifier("graphRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
