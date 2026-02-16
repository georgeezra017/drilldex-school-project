package com.drilldex.drillbackend.pack;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PackRepository extends JpaRepository<Pack, Long> {
    List<Pack> findByApprovedTrue();
    List<Pack> findByOwnerId(Long ownerId);            // for “my packs” page

    void deleteAllByOwnerId(Long ownerId);

    @EntityGraph(attributePaths = {"owner"}) // add "beats" too if you want beatsCount without extra queries
    List<Pack> findByApprovedFalseAndRejectedFalseOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"beats", "owner"})
    Optional<Pack> findWithBeatsAndOwnerById(Long id);

    @Query("""
      select distinct p
      from Pack p
      left join fetch p.beats b
      left join fetch b.owner
      where p.id = :id
    """)
    Optional<Pack> findWithBeats(@Param("id") Long id);



    @Query(value = """
    SELECT p.*
    FROM pack p
    JOIN promotion promo ON promo.target_id = p.id
    WHERE p.owner_id = :ownerId
      AND p.approved = true
      AND p.rejected = false
      AND promo.target_type = 'PACK'
      AND promo.start_date <= :now
      AND promo.start_date + (promo.duration_days * INTERVAL '1 day') > :now
    ORDER BY
      CASE promo.tier
        WHEN 'spotlight' THEN 3
        WHEN 'premium' THEN 2
        ELSE 1
      END DESC,
      promo.start_date DESC
""", nativeQuery = true)
    List<Pack> findActiveFeaturedByOwner(@Param("ownerId") Long ownerId,
                                         @Param("now") Instant now,
                                         Pageable pageable);

    // === NEW (owner-scoped, windowed) ===
    List<Pack> findByOwnerIdAndApprovedTrueAndRejectedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
            Long ownerId, Instant cutoff, Pageable pageable
    );

    // Fallback newest (owner-scoped, no explicit window)
    List<Pack> findByOwnerIdAndApprovedTrueAndRejectedFalseOrderByCreatedAtDesc(
            Long ownerId, Pageable pageable
    );

    // === POPULAR (owner-scoped, windowed: plays + 3*likes) ===
    @Query("""
        SELECT p FROM Pack p
        WHERE p.owner.id = :ownerId
          AND p.approved = true AND p.rejected = false
          AND p.createdAt >= :cutoff
        ORDER BY (COALESCE(p.playCount, 0) + 3 * COALESCE(p.likeCount, 0)) DESC,
                 p.createdAt DESC
    """)
    List<Pack> findOwnerPopularSince(@Param("ownerId") Long ownerId,
                                     @Param("cutoff") Instant cutoff,
                                     Pageable pageable);

    // === TRENDING pool (owner-scoped). Service ranks by recency-decayed score. ===
    @Query("""
        SELECT p FROM Pack p
        WHERE p.owner.id = :ownerId
          AND p.approved = true AND p.rejected = false
          AND p.createdAt >= :cutoff
        ORDER BY p.createdAt DESC
    """)
    List<Pack> findOwnerTrendingPool(@Param("ownerId") Long ownerId,
                                     @Param("cutoff") Instant cutoff,
                                     Pageable pageable);

    @Modifying
    @Query("UPDATE Pack p SET p.playCount = p.playCount + 1 WHERE p.id = :id")
    void incrementPlayCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Pack p SET p.likeCount = p.likeCount + 1 WHERE p.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    // A) Distinct beats across all packs owned by the user
    @Query("select count(distinct b.id) " +
            "from Pack p join p.beats b " +
            "where p.owner.id = :ownerId")
    long countDistinctBeatIdsInPacks(@Param("ownerId") Long ownerId);

    // B) Sum a denormalized beatsCount column

    long countByOwnerId(Long ownerId);

    @Query("select count(distinct b.id) from Pack p join p.beats b where p.owner.id = :ownerId")
    long countDistinctBeatsAcrossPacksByOwnerId(@Param("ownerId") Long ownerId);

    // Or, if Pack has a beats collection instead of beatsCount:
    @Query("select count(b) from Pack p join p.beats b where p.owner.id = :ownerId")
    long countBeatsAcrossPacksByOwnerId(@Param("ownerId") Long ownerId);


    @Query("select coalesce(sum(p.playCount), 0) from Pack p where p.owner.id = :ownerId")
    long sumPlayCountByOwnerId(@Param("ownerId") Long ownerId);

    List<Pack> findByOwnerIdAndApprovedTrueAndRejectedFalse(Long ownerId);

    Optional<Pack> findBySlug(String slug);
    boolean existsBySlug(String slug);

    // Comments — list newest first
    @Query("""
   select c from PackComment c
   where c.pack.id = :packId
   order by c.id desc
""")
    java.util.List<com.drilldex.drillbackend.pack.PackComment> findComments(@Param("packId") Long packId);

    @Query("""
   select c from PackComment c
   where c.pack.id = :packId and c.id = :commentId
""")
    java.util.Optional<com.drilldex.drillbackend.pack.PackComment> findComment(
            @Param("packId") Long packId,
            @Param("commentId") Long commentId
    );

    @Query("""
        select p from Pack p
        order by p.createdAt desc
    """)
    List<Pack> listRecent(Pageable pageable);

    @Query("""
        select p from Pack p
        where
             lower(coalesce(p.title,       '')) like lower(concat('%', :q, '%'))
          or lower(coalesce(p.description, '')) like lower(concat('%', :q, '%'))
          or lower(coalesce(p.tags,        '')) like lower(concat('%', :q, '%'))
        order by p.createdAt desc
    """)
    List<Pack> search(@Param("q") String q, Pageable pageable);

    @Query("""
        select distinct p
        from Pack p
        left join p.beats b
        where p.owner.id = :ownerId
    """)
    List<Pack> findDistinctByOwnerId(@Param("ownerId") Long ownerId);

    @Query("""
    SELECT p.id
    FROM Pack p
    JOIN p.upvotedBy u
    WHERE u.id = :userId AND p.id IN :ids
""")
    Set<Long> findLikedPackIds(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    // PACK
    @Query(
            value = """
        SELECT p FROM Pack p
        LEFT JOIN p.owner o
        WHERE p.approved = true AND (
            LOWER(COALESCE(p.title, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(p.tags, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(p.slug, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(p.genre, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(o.displayName, '')) LIKE concat('%', :qNormalized, '%')

            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(p.title, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(p.tags, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(p.slug, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(p.genre, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(o.displayName, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
        )
        ORDER BY p.createdAt DESC
    """,
            countQuery = """
        SELECT count(p) FROM Pack p
        LEFT JOIN p.owner o
        WHERE p.approved = true AND (
            LOWER(COALESCE(p.title, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(p.tags, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(p.slug, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(p.genre, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(o.displayName, '')) LIKE concat('%', :qNormalized, '%')

            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(p.title, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(p.tags, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(p.slug, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(p.genre, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(o.displayName, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
        )
    """
    )
    Page<Pack> searchFlexiblePaginated(@Param("qNormalized") String qNormalized, Pageable pageable);

    List<Pack> findByOwnerIdAndApprovedTrue(Long ownerId);

    @Query(value = """
    SELECT p.*
    FROM pack p
    JOIN promotion promo ON promo.target_id = p.id
    WHERE promo.target_type = 'PACK'
      AND promo.start_date <= :now
      AND promo.start_date + (promo.duration_days * INTERVAL '1 day') > :now
    ORDER BY
      CASE promo.tier
        WHEN 'spotlight' THEN 3
        WHEN 'premium' THEN 2
        WHEN 'standard' THEN 1
        ELSE 0
      END DESC,
      promo.start_date DESC
    """, nativeQuery = true)
    List<Pack> findActiveFeatured(@Param("now") Instant now, Pageable pageable);

    int countByOwnerIdAndCreatedAtAfter(Long ownerId, Instant after);

    List<Pack> findByApprovedTrueOrderByCreatedAtDesc(Pageable pageable);

    Page<Pack> findByApprovedTrueAndRejectedFalseOrderByCreatedAtDesc(Pageable pageable);

    long countByApprovedTrueAndRejectedFalse();

    Page<Pack> findByApprovedTrueAndRejectedFalseAndCreatedAtAfterOrderByCreatedAtDesc(Instant cutoff, Pageable pageable);

    @Query("""
    SELECT p FROM Pack p
    WHERE p.approved = true AND p.rejected = false
      AND p.createdAt >= :cutoff
    ORDER BY (p.playCount + 3 * p.likeCount) DESC
""")
    Page<Pack> findPopularSince(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("SELECT COUNT(p) FROM Pack p WHERE p.approved = true")
    int countAllApproved();

    // Count matches for search query
    @Query("""
    SELECT COUNT(p) FROM Pack p 
    WHERE p.approved = true AND (
        LOWER(p.title) LIKE %:qLike% OR
        REPLACE(LOWER(p.title), ' ', '') LIKE %:qNoSpace%
    )
""")
    int countSearchMatches(@Param("qLike") String qLike, @Param("qNoSpace") String qNoSpace);

    @Query("""
    SELECT p FROM Pack p
    WHERE p.approved = true
    ORDER BY p.createdAt DESC
""")
    Page<Pack> listRecentPaginated(Pageable pageable);


    // Strictly new packs (within window)
    List<Pack> findByApprovedTrueAndCreatedAtAfterOrderByCreatedAtDesc(
            Instant cutoff, Pageable pageable);

    // Count total new packs (for pagination metadata)
    @Query("SELECT COUNT(p) FROM Pack p WHERE p.approved = true AND p.createdAt >= :cutoff")
    int countNewPacksSince(@Param("cutoff") Instant cutoff);

    @Query("""
    SELECT p FROM Pack p
    WHERE p.approved = true AND p.rejected = false
      AND p.createdAt >= :cutoff
    ORDER BY p.createdAt DESC
""")
    List<Pack> findGlobalTrendingPool(@Param("cutoff") Instant cutoff, Pageable pageable);


    @Query("""
    SELECT p FROM Pack p
    WHERE p.approved = true AND p.rejected = false
      AND p.createdAt >= :cutoff
    ORDER BY (p.playCount + 3 * p.likeCount) DESC
""")
    List<Pack> findGlobalPopularSince(@Param("cutoff") Instant cutoff, Pageable pageable);





}
