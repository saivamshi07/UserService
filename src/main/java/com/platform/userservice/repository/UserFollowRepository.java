package com.platform.userservice.repository;

import com.platform.userservice.entity.FollowStatus;
import com.platform.userservice.entity.UserFollow;
import com.platform.userservice.entity.UserFollowId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserFollowRepository extends JpaRepository<UserFollow, UserFollowId> {

    Optional<UserFollow> findByIdFollowerIdAndIdFollowingId(UUID followerId, UUID followingId);

    boolean existsByIdFollowerIdAndIdFollowingId(UUID followerId, UUID followingId);

    boolean existsByIdFollowerIdAndIdFollowingIdAndStatus(UUID followerId, UUID followingId, FollowStatus status);

    long countByIdFollowingIdAndStatus(UUID followingId, FollowStatus status);

    long countByIdFollowerIdAndStatus(UUID followerId, FollowStatus status);

    Page<UserFollow> findByIdFollowingIdAndStatus(UUID followingId, FollowStatus status, Pageable pageable);

    Page<UserFollow> findByIdFollowerIdAndStatus(UUID followerId, FollowStatus status, Pageable pageable);

    List<UserFollow> findByIdFollowerIdAndStatus(UUID followerId, FollowStatus status);

    @Query("SELECT uf.id.followingId FROM UserFollow uf WHERE uf.id.followerId = :followerId AND uf.status = :status")
    List<UUID> findFollowingIdsByFollowerIdAndStatus(@Param("followerId") UUID followerId, @Param("status") FollowStatus status);

    void deleteByIdFollowerIdAndIdFollowingId(UUID followerId, UUID followingId);
}
