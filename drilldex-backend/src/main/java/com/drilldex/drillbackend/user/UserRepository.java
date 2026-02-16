package com.drilldex.drillbackend.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByDisplayNameIgnoreCase(String displayName);

    Optional<User> findByReferralCode(String referralCode);


    // Search users by display name or email (case-insensitive) and only include non-banned users
    Page<User> findByDisplayNameContainingIgnoreCaseOrEmailContainingIgnoreCaseAndBannedFalse(
            String displayName,
            String email,
            Pageable pageable
    );

    // Get all non-banned users, sorted alphabetically by display name
    Page<User> findAllByBannedFalseOrderByDisplayNameAsc(Pageable pageable);

    // Get all non-banned users (no particular sorting)
    Page<User> findAllByBannedFalse(Pageable pageable);

    @Query("SELECT COUNT(u) FROM User u JOIN u.followers f WHERE u.id = :userId")
    long countFollowers(@Param("userId") Long userId);

    Optional<User> findByGoogleId(String googleId);

    @Query("SELECT COUNT(u) FROM User u WHERE u.role = 'PRODUCER'")
    long countProducers();

    /** Count producers matching a search query (for pagination) */
    @Query("""
    SELECT COUNT(u)
    FROM User u
    WHERE u.role = 'PRODUCER'
      AND lower(coalesce(nullif(u.displayName, ''), u.email)) LIKE lower(concat('%', :q, '%'))
""")
    long countSearchProducers(@Param("q") String q);

    @Query("SELECT f FROM User u JOIN u.followers f WHERE u.id = :userId")
    List<User> findFollowersByUserId(@Param("userId") Long userId);

    List<User> findTop10ByDisplayNameIgnoreCaseContainingOrEmailIgnoreCaseContaining(String namePart, String emailPart);


}
