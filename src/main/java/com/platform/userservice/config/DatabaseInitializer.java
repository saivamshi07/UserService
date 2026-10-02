package com.platform.userservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("Ensuring PostgreSQL pg_trgm extension and search indexes are configured...");
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm;");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_users_username_trgm ON users USING gin (username gin_trgm_ops);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_users_bio_trgm ON users USING gin (bio gin_trgm_ops);");
            log.info("PostgreSQL pg_trgm extension and GIN trigram indexes ready.");
        } catch (Exception e) {
            log.warn("Could not create pg_trgm extension or indexes automatically: {}", e.getMessage());
        }
    }
}
