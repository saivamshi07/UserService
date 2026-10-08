# Project: user-service (Microservices Architecture)

## 1. Tech Stack
- Framework: Spring Boot 3.x / 4.x, Java 21
- Database: PostgreSQL 17 (Relational core + JSONB attributes + `pg_trgm` GIN indexes for fuzzy search)
- Caching & Sessions: Redis 7 (`spring-boot-starter-data-redis` + `StringRedisTemplate`)
- Messaging: Apache Kafka (KRaft mode) + Transactional Outbox Pattern (`outbox_events` table + scheduled publisher)
- Object Storage: S3 / MinIO via AWS SDK v2 (`software.amazon.awssdk:s3:2.29.50`)
- Google Auth: Google API Client library (`com.google.api-client:google-api-client:2.7.0`)
- Security: Spring Security Crypto (`BCryptPasswordEncoder`) + JJWT 0.12.6 + `JwtAuthenticationFilter`

## 2. Configuration & Policy-Driven Architecture
- Dynamic policy management using `src/main/resources/user-policy.yml`
- Injected via `spring.config.import=classpath:user-policy.yml` in `application.properties`
- Properties mapped to `@ConfigurationProperties(prefix = "user-policy") UserPolicyConfig`
- Config toggles: Auth modes (EITHER, BOTH, ONLY_EMAIL, ONLY_PHONE), name validation regex/length, bio limits, profile picture restrictions (5MB, JPEG/PNG/WEBP), privacy toggles, deactivation rules, Google OAuth toggle (`allow-google: true`).

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

4. `outbox_events` Table:
    - `id` (UUID PK)
    - `aggregate_type` (VARCHAR 50, e.g. "USER")
    - `aggregate_id` (VARCHAR 100, user UUID)
    - `event_type` (VARCHAR 100, e.g. "USER_REGISTERED", "USER_DEACTIVATED")
    - `topic` (VARCHAR 100, e.g. "user.registered.v1", "user.deactivated.v1")
    - `payload` (TEXT / JSON)
    - `status` (ENUM: PENDING, PUBLISHED, FAILED)
    - `retry_count` (INT, default 0)
    - `error_message` (TEXT, nullable)
    - `created_at` (TIMESTAMP), `processed_at` (TIMESTAMP, nullable)
    - Indexes: `(status, created_at)` and `(aggregate_type, aggregate_id)`

## 4. Search Architecture (`pg_trgm`)
- PostgreSQL `pg_trgm` extension enabled automatically via `DatabaseInitializer`.
- Native inverted GIN indexes on `username` and `bio`.
- Native query in `UserRepository.searchUsers(query, pageable)` supporting:
  - Exact prefix matching prioritization
  - Typo-tolerant trigram similarity ranking (`similarity > 0.2` on username, `> 0.15` on bio)
  - Substring matching via `ILIKE`

## 5. Auth, Redis & Security Design
- **Anti-Spoofing & Context Security**:
  - `JwtAuthenticationFilter`: Extracts and cryptographically validates JWT tokens from `access_token` cookie or `Authorization: Bearer <token>`.
  - Raw unverified `X-User-Id` headers are rejected/ignored unless accompanied by a verified `X-Gateway-Secret`.
  - `UserContext`: ThreadLocal storing authenticated user ID, securely accessed via `UserContext.getRequiredUserId()` and automatically cleared after request completion.
- **Session Tokens & Redis Storage**:
  - Stateless Access Tokens (15-min TTL) delivered via `HttpOnly`, `Secure`, `SameSite=Strict` cookies.
  - Refresh Tokens (7-day TTL) delivered via `HttpOnly`, `Secure`, `SameSite=Strict` cookies restricted to `/api/v1/auth`.
  - `RedisSessionService`:
    - Stores `refresh_token:<jti> -> userId` with 7-day TTL.
    - Tracks active user sessions in `user_sessions:<userId>` sets.
    - Token Rotation: Rotates refresh token on each `/refresh` call (old jti deleted, new jti persisted).
    - Immediate Revocation: On `/logout`, deletes `refresh_token:<jti>` from Redis.
    - Complete Account Revocation: On `deactivateUser()`, purges all active session keys across all user devices.
- Passwords hashed using `BCryptPasswordEncoder`.
- Google OAuth: Verifies Google ID tokens via `POST /api/v1/auth/google`, auto-provisions or links existing accounts.

## 6. S3 / MinIO Profile Picture Presigned URLs & Storage Lifecycle
- Direct-to-storage architecture offloads heavy binary transfers from the Spring Boot application.
- `S3Config` provides both `S3Presigner` (for client SigV4 upload URL signing) and `S3Client` (for backend operations like deletion).
- `POST /api/v1/users/me/avatar/presigned-url`:
  - Enforces `user-policy.yml` maximum size limits (5 MB).
  - Enforces allowed MIME types (`image/jpeg`, `image/png`, `image/webp`).
  - Generates partitioned S3 key `avatars/{userId}/{randomUUID}.{ext}`.
  - Returns presigned `uploadUrl`, public `fileUrl`, `s3Key`, and `expiresAt` (15 minutes).
- **Automatic Storage Lifecycle & Orphan Prevention**:
  - When user updates profile with a new `pictureUrl` via `UserService.updateUserProfile`, the old `s3Key` is parsed and `AvatarStorageService.deleteAvatar` immediately removes the previous image from S3/MinIO.
  - Storage deletion exceptions are safely caught so S3 connection blips do not fail user profile updates.

## 7. Apache Kafka & Transactional Outbox Pattern
- **Atomic Event Publishing**:
  - Outbox records are inserted into `outbox_events` within the same DB transaction as business logic via `@Transactional(propagation = Propagation.MANDATORY)` in `OutboxService`.
  - Prevents dual-write failures (e.g. database commits but Kafka is down).
- **Events Published**:
  - `user.registered.v1`: Aggregate ID, username, email, phone, timestamp.
  - `user.deactivated.v1`: Aggregate ID, username, disabledAt timestamp.
- **Outbox Publisher Scheduler**:
  - `OutboxPublisherService` runs periodically (`@Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:2000}")`).
  - Polls batches of `PENDING` events.
  - Emits asynchronously using `KafkaTemplate` with retry tracking (increments `retryCount`, marks `FAILED` after 5 attempts).
  - On delivery ack, flips status to `PUBLISHED` with `processedAt` timestamp.

## 8. Container Orchestration (`docker-compose.yml`)
- Multi-container environment orchestrating:
  - `postgres`: PostgreSQL 17-alpine with database healthcheck (`pg_isready`).
  - `redis`: Redis 7-alpine with ping healthcheck.
  - `minio`: MinIO object storage with automatic console on port 9001 and live healthcheck.
  - `create-buckets`: One-shot `minio/mc` container automatically creating `user-avatars` bucket with public read access.
  - `kafka`: Apache Kafka 3.8.0 in KRaft mode (no Zookeeper required) with broker API healthcheck.

## 9. Current Progress
- All 19 unit & integration tests passing (`BUILD SUCCESS`).
- 100% test coverage over Registration, Trigram Search, Cookies/Auth lifecycle, Redis Session Revocation, Security Anti-Spoofing, S3 Presigned URLs & Cleanup, and Kafka Transactional Outbox.
