package com.platform.userservice.repository;

import com.platform.userservice.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    /**
     * Search users using PostgreSQL pg_trgm similarity and prefix/substring matching.
     * Orders by exact prefix matches first, then descending similarity on username and bio.
     */
    @Query(value = """
            SELECT *
            FROM users u
            WHERE u.is_active = true
              AND (
                  u.username ILIKE concat('%', :query, '%')
                  OR (u.bio IS NOT NULL AND u.bio ILIKE concat('%', :query, '%'))
                  OR similarity(u.username, :query) > 0.2
                  OR similarity(coalesce(u.bio, ''), :query) > 0.15
              )
            ORDER BY
                CASE WHEN u.username ILIKE concat(:query, '%') THEN 1 ELSE 2 END,
                similarity(u.username, :query) DESC,
                similarity(coalesce(u.bio, ''), :query) DESC
            """,
            countQuery = """
            SELECT count(*)
            FROM users u
            WHERE u.is_active = true
              AND (
                  u.username ILIKE concat('%', :query, '%')
                  OR (u.bio IS NOT NULL AND u.bio ILIKE concat('%', :query, '%'))
                  OR similarity(u.username, :query) > 0.2
                  OR similarity(coalesce(u.bio, ''), :query) > 0.15
              )
            """,
            nativeQuery = true)
    Page<User> searchUsers(@Param("query") String query, Pageable pageable);
}
