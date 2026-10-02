# Project: user-service (Microservices Architecture)

## 1. Tech Stack
- Framework: Spring Boot 3.x / 4.x, Java 21
- Database: PostgreSQL 17 (Relational core + JSONB attributes + `pg_trgm` GIN indexes for fuzzy search)
- Caching & Sessions: Redis
- Messaging: Apache Kafka (Transactional Outbox pattern planned)
- Object Storage: S3 / MinIO (Presigned URLs for profile pictures)
- Google Auth: Google API Client library (`com.google.api-client:google-api-client:2.7.0`)
- Security: Spring Security Crypto (`BCryptPasswordEncoder`)

## 2. Configuration & Policy-Driven Architecture
- Dynamic policy management using `src/main/resources/user-policy.yml`
- Injected via `spring.config.import=classpath:user-policy.yml` in `application.properties`
- Properties mapped to `@ConfigurationProperties(prefix = "user-policy") UserPolicyConfig`
- Config toggles: Auth modes (EITHER, BOTH, ONLY_EMAIL, ONLY_PHONE), name validation regex/length, bio limits, profile picture restrictions, privacy toggles, deactivation rules, Google OAuth toggle (`allow-google: true`).

## 3. Database Schema & Entities
1. `users` Table:
    - `id` (UUID PK)
    - `username` (VARCHAR 50, UNIQUE, NOT NULL)
    - `email` (VARCHAR 100, UNIQUE, NULLABLE)
    - `phone` (VARCHAR 20, UNIQUE, NULLABLE)
    - `password_hash` (VARCHAR 255, NULLABLE for OAuth)
    - Status: `is_email_verified`, `is_phone_verified`, `is_active`, `disabled_at`
    - Privacy flags: `is_private`, `is_email_private`, `is_phone_private`, `is_picture_private`
    - Flexible attributes: `bio` (VARCHAR 160), `picture_url` (VARCHAR 500), `public_profiles` (JSONB)
    - Timestamps: `created_at`, `updated_at`
    - Indexes: GIN trigram indexes `idx_users_username_trgm` and `idx_users_bio_trgm` via `pg_trgm`

2. `user_follows` Table:
    - Composite PK: `follower_id` (UUID), `following_id` (UUID) via `@Embeddable UserFollowId`
    - `status` (ENUM: ACCEPTED, PENDING, REJECTED)
    - `created_at` (TIMESTAMP)
    - Index: `(following_id, status)`

3. `user_audit_logs` Table:
    - `id` (BIGSERIAL PK)
    - `user_id` (UUID, nullable on delete)
    - `activity` (VARCHAR 100)
    - `ip_address` (VARCHAR 45), `user_agent` (VARCHAR 255)
    - `details` (JSONB)
    - `created_at` (TIMESTAMP)

## 4. Search Architecture (`pg_trgm`)
- PostgreSQL `pg_trgm` extension enabled automatically via `DatabaseInitializer`.
- Native inverted GIN indexes on `username` and `bio`.
- Native query in `UserRepository.searchUsers(query, pageable)` supporting:
  - Exact prefix matching prioritization
  - Typo-tolerant trigram similarity ranking (`similarity > 0.2` on username, `> 0.15` on bio)
  - Substring matching via `ILIKE`

## 5. Auth & Security Design
- JWT stateless access tokens (15-min TTL) delivered via `HttpOnly`, `Secure`, `SameSite=Strict` cookies.
- Long-lived refresh tokens (7-day TTL) stored in Redis (`refresh_token:<token> -> userId`).
- Passwords hashed using `BCryptPasswordEncoder`.
- Google OAuth: Verifies Google ID tokens via `POST /api/v1/auth/google`, auto-provisions or links existing accounts.
- API Gateway acts as the perimeter barrier: extracts cookie, verifies signature statelessly, injects downstream headers (`X-User-Id`, `X-User-Roles`), and strips incoming `X-User-*` headers from external clients.

## 6. Current Progress
- Configured: `user-policy.yml`, `application.properties`, and `UserPolicyConfig`
- Entities defined: `User`, `UserFollow`, `UserFollowId`, `UserAuditLog`
- Repositories implemented: `UserRepository` (with `pg_trgm` search), `UserFollowRepository`, `UserAuditLogRepository`
- DatabaseInitializer: Guarantees `pg_trgm` and GIN indexes exist on startup
- DTOs & Custom Exceptions: `RegisterRequest`, `LoginRequest`, `GoogleAuthRequest`, `UserResponse`, `UpdateUserRequest`, `UserSearchResponse`, `UserFollowResponse`, `GlobalExceptionHandler`
- Business Services: `PolicyValidatorService`, `GoogleAuthService`, `UserService`, `UserFollowService`
- REST Controllers: `AuthController`, `UserController`, `UserFollowController`
- Automated Tests: Passing end-to-end integration tests verifying registration, auditing, and pg_trgm search.

## 7. Next Tasks
1. JWT Issuance & Cookie Management (Session tokens delivered via Secure HttpOnly cookies).
2. Redis Integration (Refresh token storage with 7-day TTL).
3. Apache Kafka Event Publishing (e.g. `USER_REGISTERED`, `USER_DEACTIVATED`).
4. MinIO / S3 Profile Picture Presigned Upload URL generation.
