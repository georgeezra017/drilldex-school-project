// src/main/java/com/drilldex/drillbackend/kit/KitRepository.java
package com.drilldex.drillbackend.kit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface KitRepository extends JpaRepository<Kit, Long> {
//    List<Kit> findByOwnerId(Long ownerId);
    List<Kit> findByStatusIgnoreCaseOrderByCreatedAtDesc(String status);
    List<Kit> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM Kit k WHERE k.owner.id = :ownerId")
    void deleteAllByOwnerId(@Param("ownerId") Long ownerId);

//    @Query("""
//        SELECT k FROM Kit k
//        WHERE LOWER(k.status) = 'published'
//          AND k.featured = true
//          AND k.featuredUntil IS NOT NULL
//          AND k.featuredUntil > :now
//        ORDER BY
//          CASE LOWER(COALESCE(k.featuredTier, 'standard'))
//            WHEN 'spotlight' THEN 3
//            WHEN 'premium'   THEN 2
//            ELSE 1
//          END DESC,
//          COALESCE(k.featuredFrom, k.createdAt) DESC
//    """)
//    List<Kit> findActiveFeatured(@Param("now") Instant now, Pageable pageable);

    // --- NEW: legacy boolean fallback
    List<Kit> findByFeaturedTrueAndStatusIgnoreCaseOrderByFeaturedAtDesc(String status, Pageable pageable);

    List<Kit> findByOwnerId(Long ownerId);

    /* ===== FEATURED (global & owner) ===== */

    @Query("""
    SELECT k FROM Kit k
    WHERE LOWER(k.status) = 'published'
      AND (
           (k.featuredUntil IS NOT NULL AND k.featuredUntil > :now)
        OR (k.featured = true AND k.featuredUntil IS NULL)
      )
    ORDER BY
      CASE
        WHEN LOWER(COALESCE(k.featuredTier, 'standard')) = 'spotlight' THEN 3
        WHEN LOWER(COALESCE(k.featuredTier, 'standard')) = 'premium'   THEN 2
        ELSE 1
      END DESC,
      COALESCE(k.featuredFrom, k.featuredAt, k.createdAt) DESC
""")
    List<Kit> findActiveFeatured(@Param("now") Instant now, Pageable pageable);

    @Query("""
    SELECT k FROM Kit k
    WHERE k.owner.id = :ownerId
      AND LOWER(k.status) = 'published'
      AND (
           (k.featuredUntil IS NOT NULL AND k.featuredUntil > :now)
        OR (k.featured = true AND k.featuredUntil IS NULL)
      )
    ORDER BY
      CASE
        WHEN LOWER(COALESCE(k.featuredTier, 'standard')) = 'spotlight' THEN 3
        WHEN LOWER(COALESCE(k.featuredTier, 'standard')) = 'premium'   THEN 2
        ELSE 1
      END DESC,
      COALESCE(k.featuredFrom, k.featuredAt, k.createdAt) DESC
""")
    List<Kit> findActiveFeaturedByOwner(@Param("ownerId") Long ownerId,
                                        @Param("now") Instant now,
                                        Pageable pageable);

    @Modifying
    @Query("UPDATE Kit k SET k.playCount = k.playCount + 1 WHERE k.id = :id")
    void incrementPlayCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Kit k SET k.likeCount = k.likeCount + 1 WHERE k.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    // newest published within window
    List<Kit> findByOwnerIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
            Long ownerId, String status, Instant cutoff, Pageable pageable);

    // fallback: newest published (no window)
    List<Kit> findByOwnerIdAndStatusOrderByCreatedAtDesc(
            Long ownerId, String status, Pageable pageable);

    /* ===== POPULAR (downloads over a window) ===== */


    @Query("""
        SELECT k FROM Kit k
        WHERE LOWER(k.status) = 'published'
          AND k.owner.id = :ownerId
          AND k.createdAt >= :cutoff
        ORDER BY (COALESCE(k.playCount, 0) + 3 * COALESCE(k.likeCount, 0)) DESC,
                 k.createdAt DESC
    """)
    List<Kit> findOwnerPopularSince(@Param("ownerId") Long ownerId,
                                    @Param("cutoff") Instant cutoff,
                                    Pageable pageable);

    /* ===== TRENDING POOL (ranked in service) ===== */

    @Query("""
        SELECT k FROM Kit k
        WHERE LOWER(k.status) = 'published'
          AND k.owner.id = :ownerId
          AND k.createdAt >= :cutoff
        ORDER BY k.createdAt DESC
    """)
    List<Kit> findOwnerTrendingPool(@Param("ownerId") Long ownerId,
                                    @Param("cutoff") Instant cutoff,
                                    Pageable pageable);

    long countByOwnerId(Long ownerId);


    @Query("select coalesce(sum(k.samplesCount), 0) from Kit k where k.owner.id = :ownerId")
    long sumSamplesByOwnerId(@Param("ownerId") Long ownerId);

    @Query("select coalesce(sum(k.loopsCount), 0) from Kit k where k.owner.id = :ownerId")
    long sumLoopsByOwnerId(@Param("ownerId") Long ownerId);

    @Query("select coalesce(sum(k.presetsCount), 0) from Kit k where k.owner.id = :ownerId")
    long sumPresetsByOwnerId(@Param("ownerId") Long ownerId);

    // Combined “sound units” = samples + loops + presets
    @Query("select coalesce(sum(k.samplesCount + k.loopsCount + k.presetsCount), 0) " +
            "from Kit k where k.owner.id = :ownerId")
    long sumSoundUnitsByOwnerId(@Param("ownerId") Long ownerId);

    @Query("select coalesce(sum(k.durationInSeconds), 0) from Kit k where k.owner.id = :ownerId")
    long sumDurationByOwnerId(@Param("ownerId") Long ownerId);

    // total plays across all kits owned by a user
    @Query("select coalesce(sum(k.playCount), 0) from Kit k where k.owner.id = :ownerId")
    long sumPlayCountByOwnerId(@Param("ownerId") Long ownerId);

    boolean existsBySlug(String slug);
    Optional<Kit> findBySlug(String slug);

    @Query("""
        SELECT c FROM KitComment c
        WHERE c.kit.id = :kitId
          AND (:cursor IS NULL OR c.id < :cursor)
        ORDER BY c.id DESC
    """)
    List<KitComment> findKitComments(
            @Param("kitId") Long kitId,
            @Param("cursor") Long cursor,
            Pageable pageable);

    @Query("SELECT COUNT(c) FROM KitComment c WHERE c.kit.id = :kitId")
    long countKitComments(@Param("kitId") Long kitId);


    @Query("""
       SELECT c FROM KitComment c
       WHERE c.kit.id = :kitId
       ORDER BY c.createdAt DESC
       """)
    List<KitComment> findComments(@Param("kitId") Long kitId);

    @Query("""
       SELECT c FROM KitComment c
       WHERE c.kit.id = :kitId AND c.id = :commentId
       """)
    Optional<KitComment> findComment(@Param("kitId") Long kitId, @Param("commentId") Long commentId);


    @Query("""
    select k from Kit k
    order by k.createdAt desc
""")
    List<Kit> listRecent(Pageable pageable);

    @Query("""
    select k from Kit k
    where
         lower(coalesce(k.title,       '')) like lower(concat('%', :q, '%'))
      or lower(coalesce(k.description, '')) like lower(concat('%', :q, '%'))
      or lower(coalesce(k.tags,        '')) like lower(concat('%', :q, '%'))
      or lower(coalesce(k.type,        '')) like lower(concat('%', :q, '%'))
    order by k.createdAt desc
""")
    List<Kit> search(@Param("q") String q, Pageable pageable);

    @Query("""
SELECT k FROM Kit k
WHERE LOWER(k.status) = 'published'
ORDER BY
  CASE
    WHEN (k.featuredUntil IS NOT NULL AND k.featuredUntil > :now)
      OR (k.featured = true AND k.featuredUntil IS NULL)
    THEN CASE LOWER(COALESCE(k.featuredTier, 'standard'))
           WHEN 'spotlight' THEN 3
           WHEN 'premium'   THEN 2
           ELSE 1
         END
    ELSE 0
  END DESC,
  COALESCE(k.featuredFrom, k.featuredAt, k.createdAt) DESC
""")
    List<Kit> findPublishedForBrowse(@Param("now") java.time.Instant now,
                                     org.springframework.data.domain.Pageable pageable);

    @Query("""
    SELECT k.id
    FROM Kit k
    JOIN k.upvotedBy u
    WHERE u.id = :userId
      AND k.id IN :ids
""")
    Set<Long> findLikedKitIds(@Param("userId") Long userId,
                              @Param("ids") List<Long> ids);






    @Query(
            value = """
        SELECT k FROM Kit k
        WHERE
          lower(coalesce(k.title, '')) LIKE concat('%', :qLike, '%')
          OR lower(coalesce(k.tags,  '')) LIKE concat('%', :qLike, '%')
          OR lower(coalesce(k.slug,  '')) LIKE concat('%', :qLike, '%')
          OR lower(coalesce(k.type,  '')) LIKE concat('%', :qLike, '%')

          OR replace(lower(coalesce(k.title, '')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR replace(lower(coalesce(k.tags,  '')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR replace(lower(coalesce(k.slug,  '')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR replace(lower(coalesce(k.type,  '')), ' ', '') LIKE concat('%', :qNoSpace, '%')
        ORDER BY k.createdAt DESC
    """,
            countQuery = """
        SELECT count(k) FROM Kit k
        WHERE
          lower(coalesce(k.title, '')) LIKE concat('%', :qLike, '%')
          OR lower(coalesce(k.tags,  '')) LIKE concat('%', :qLike, '%')
          OR lower(coalesce(k.slug,  '')) LIKE concat('%', :qLike, '%')
          OR lower(coalesce(k.type,  '')) LIKE concat('%', :qLike, '%')
          OR replace(lower(coalesce(k.title, '')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR replace(lower(coalesce(k.tags,  '')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR replace(lower(coalesce(k.slug,  '')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR replace(lower(coalesce(k.type,  '')), ' ', '') LIKE concat('%', :qNoSpace, '%')
    """
    )
    List<Kit> searchFlexible(@Param("qLike") String qLike,
                             @Param("qNoSpace") String qNoSpace,
                             Pageable pageable);

    @Query("""
   select k
   from Kit k
   where k.owner.id = :ownerId
     and lower(k.status) = 'published'
   order by k.createdAt desc
""")
    List<Kit> findPublishedByOwner(@Param("ownerId") Long ownerId, Pageable pageable);

    int countByOwnerIdAndCreatedAtAfter(Long ownerId, Instant after);

    @Query("""
    SELECT COUNT(k)
    FROM Kit k
    WHERE k.owner.id = :ownerId
      AND k.createdAt >= :cutoff
""")
    int countNewKitsByOwner(@Param("ownerId") Long ownerId, @Param("cutoff") Instant cutoff);

    @Query("SELECT k FROM Kit k WHERE k.status = 'published' ORDER BY k.createdAt DESC")
    Page<Kit> listRecentPaginated(Pageable pageable);

    Page<Kit> findByOwnerId(Long ownerId, Pageable pageable);

    @Query(
            value = """
        SELECT k FROM Kit k
        WHERE
          LOWER(COALESCE(k.title,'')) LIKE concat('%', :qLike, '%')
          OR LOWER(COALESCE(k.tags,'')) LIKE concat('%', :qLike, '%')
          OR LOWER(COALESCE(k.slug,'')) LIKE concat('%', :qLike, '%')
          OR LOWER(COALESCE(k.type,'')) LIKE concat('%', :qLike, '%')

          OR REPLACE(LOWER(COALESCE(k.title,'')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR REPLACE(LOWER(COALESCE(k.tags,'')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR REPLACE(LOWER(COALESCE(k.slug,'')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR REPLACE(LOWER(COALESCE(k.type,'')), ' ', '') LIKE concat('%', :qNoSpace, '%')
        ORDER BY k.createdAt DESC
    """,
            countQuery = """
        SELECT count(k) FROM Kit k
        WHERE
          LOWER(COALESCE(k.title,'')) LIKE concat('%', :qLike, '%')
          OR LOWER(COALESCE(k.tags,'')) LIKE concat('%', :qLike, '%')
          OR LOWER(COALESCE(k.slug,'')) LIKE concat('%', :qLike, '%')
          OR LOWER(COALESCE(k.type,'')) LIKE concat('%', :qLike, '%')

          OR REPLACE(LOWER(COALESCE(k.title,'')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR REPLACE(LOWER(COALESCE(k.tags,'')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR REPLACE(LOWER(COALESCE(k.slug,'')), ' ', '') LIKE concat('%', :qNoSpace, '%')
          OR REPLACE(LOWER(COALESCE(k.type,'')), ' ', '') LIKE concat('%', :qNoSpace, '%')
    """
    )
    Page<Kit> searchFlexiblePaginated(
            @Param("qLike") String qLike,
            @Param("qNoSpace") String qNoSpace,
            Pageable pageable
    );


    @Query("""
    SELECT k FROM Kit k
    WHERE k.status = 'published' 
      AND k.createdAt >= :cutoff
    ORDER BY (k.playCount + 3*k.likeCount) DESC
""")
    List<Kit> findPopularSince(@Param("cutoff") Instant cutoff, Pageable pageable);


}

