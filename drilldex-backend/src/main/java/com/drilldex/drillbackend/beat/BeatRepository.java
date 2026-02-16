package com.drilldex.drillbackend.beat;

//import com.drilldex.drillbackend.beat.Beat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;


import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public interface BeatRepository extends JpaRepository<Beat, Long> {
    // You can add search/filter methods later

    void deleteAllByOwnerId(Long ownerId);

    @Query("SELECT b FROM Beat b WHERE b.approved = false AND b.rejected = false AND b.partOfPack = false")
    List<Beat> findPendingStandaloneBeats();

    List<Beat> findByOwnerId(Long ownerId);

//    @Query("SELECT b FROM Beat b LEFT JOIN b.upvotedBy u GROUP BY b ORDER BY COUNT(u) DESC")
//    List<Beat> findAllOrderByUpvotesDesc();

    @Query("SELECT b FROM Beat b LEFT JOIN b.upvotedBy u WHERE b.approved = true GROUP BY b ORDER BY COUNT(u) DESC")
    List<Beat> findApprovedOrderByUpvotesDesc();

    @Modifying
    @Query("UPDATE Beat b SET b.playCount = b.playCount + 1 WHERE b.id = :id")
    void incrementPlayCount(@Param("id") Long id);

//    @Query("SELECT b FROM Beat b WHERE " +
//            "b.approved = true AND (" +
//            "LOWER(b.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
//            "LOWER(b.artist) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
//            "LOWER(b.genre) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
//            "LOWER(b.tags) LIKE LOWER(CONCAT('%', :query, '%'))" +
//            ")")
//    List<Beat> searchApprovedBeats(@Param("query") String query);

//    @Query(value = """
//    SELECT * FROM beat
//    WHERE approved = true
//    AND to_tsvector('english',
//        coalesce(title, '') || ' ' ||
//        coalesce(genre, '') || ' ' ||
//        coalesce(tags, '')
//    ) @@ plainto_tsquery('english', :query)
//    ORDER BY ts_rank(
//        to_tsvector('english',
//            coalesce(title, '') || ' ' ||
//            coalesce(genre, '') || ' ' ||
//            coalesce(tags, '')
//        ), plainto_tsquery('english', :query)
//    ) DESC
//    """,
//            nativeQuery = true)
//    List<Beat> fullTextSearch(@Param("query") String query);

    @Query(value = """
            SELECT * FROM beat
            WHERE approved = true
            AND to_tsvector('english',
                replace(coalesce(title, ''), '-', ' ') || ' ' ||
                replace(coalesce(genre, ''), '-', ' ') || ' ' ||
                replace(coalesce(tags, ''), '-', ' ')
            ) @@ plainto_tsquery('english', replace(:query, '-', ' '))
            ORDER BY ts_rank(
                to_tsvector('english',
                    replace(coalesce(title, ''), '-', ' ') || ' ' ||
                    replace(coalesce(genre, ''), '-', ' ') || ' ' ||
                    replace(coalesce(tags, ''), '-', ' ')
                ),
                plainto_tsquery('english', replace(:query, '-', ' '))
            ) DESC
            """,
            nativeQuery = true)
    List<Beat> fullTextSearch(@Param("query") String query);

//    @Query("""
//    SELECT b FROM Beat b
//    WHERE b.approved = true
//    AND (:genre IS NULL OR LOWER(b.genre) = LOWER(:genre))
//    AND (:bpmMin IS NULL OR b.bpm >= :bpmMin)
//    AND (:bpmMax IS NULL OR b.bpm <= :bpmMax)
//    """)
//    List<Beat> findByFilters(@Param("genre") String genre,
//                             @Param("bpmMin") Integer bpmMin,
//                             @Param("bpmMax") Integer bpmMax);

    @Query("""
                SELECT b FROM Beat b
                WHERE b.approved = true
                AND (:genre IS NULL OR LOWER(CAST(b.genre AS string)) = LOWER(CAST(:genre AS string)))
                AND (:bpmMin IS NULL OR b.bpm >= :bpmMin)
                AND (:bpmMax IS NULL OR b.bpm <= :bpmMax)
            """)
    List<Beat> findByFilters(
            @Param("genre") String genre,
            @Param("bpmMin") Integer bpmMin,
            @Param("bpmMax") Integer bpmMax
    );



    @Query("""
SELECT b
FROM Beat b
WHERE b.approved = true
  AND (:genre IS NULL OR lower(cast(b.genre as string)) = lower(cast(:genre as string)))
  AND (:bpmMin IS NULL OR b.bpm >= :bpmMin)
  AND (:bpmMax IS NULL OR b.bpm <= :bpmMax)
  AND (
       (:tag1 IS NULL AND :tag2 IS NULL AND :tag3 IS NULL)
       OR (
           (:tag1 IS NOT NULL AND b.tags IS NOT NULL AND
               lower(cast(b.tags as string)) LIKE concat('%', lower(cast(:tag1 as string)), '%'))
        OR (:tag2 IS NOT NULL AND b.tags IS NOT NULL AND
               lower(cast(b.tags as string)) LIKE concat('%', lower(cast(:tag2 as string)), '%'))
        OR (:tag3 IS NOT NULL AND b.tags IS NOT NULL AND
               lower(cast(b.tags as string)) LIKE concat('%', lower(cast(:tag3 as string)), '%'))
       )
  )
""")
    List<Beat> filterWithTags(
            @Param("genre") String genre,
            @Param("bpmMin") Integer bpmMin,
            @Param("bpmMax") Integer bpmMax,
            @Param("tag1") String tag1,
            @Param("tag2") String tag2,
            @Param("tag3") String tag3
    );


    List<Beat> findByApprovedTrueAndRejectedFalseOrderByIdDesc();

    // FEATURED
    List<Beat> findByFeaturedTrueOrderByFeaturedAtDesc(org.springframework.data.domain.Pageable pageable);

    // NEW (time window)
    List<Beat> findByApprovedTrueAndCreatedAtAfterOrderByCreatedAtDesc(
            java.time.Instant cutoff,
            org.springframework.data.domain.Pageable pageable
    );

    // Fallback newest (no explicit window)
    List<Beat> findByApprovedTrueAndRejectedFalseOrderByCreatedAtDesc(
            org.springframework.data.domain.Pageable pageable
    );

    // POPULAR windowed: plays + 3*likes (uses upvotedBy size)
    @org.springframework.data.jpa.repository.Query("""
        SELECT b FROM Beat b
        WHERE b.approved = true AND b.rejected = false AND b.createdAt >= :cutoff
        ORDER BY (b.playCount + 3 * SIZE(b.upvotedBy)) DESC, b.createdAt DESC
    """)
    java.util.List<Beat> findPopularSince(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff,
                                          org.springframework.data.domain.Pageable pageable);

    // Helper for TRENDING pool (we'll rank in Java)
    List<Beat> findByApprovedTrueAndRejectedFalseAndCreatedAtAfter(
            java.time.Instant cutoff,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("""
  SELECT b FROM Beat b
  WHERE b.featuredUntil IS NOT NULL AND b.featuredUntil > :now
  ORDER BY
    CASE LOWER(COALESCE(b.featuredTier, 'standard'))
      WHEN 'spotlight' THEN 3
      WHEN 'premium'   THEN 2
      ELSE 1
    END DESC,
    COALESCE(b.featuredFrom, b.createdAt) DESC
""")
    List<Beat> findActiveFeatured(@Param("now") Instant now, Pageable pageable);

    List<Beat> findByOwnerIdAndApprovedTrue(Long ownerId, Pageable pageable);

    List<Beat> findByOwnerIdAndApprovedTrueAndFeaturedTrue(Long ownerId, Pageable pageable);

    // --- NEW (owner-scoped) ---

    List<Beat> findByOwnerIdAndApprovedTrueAndRejectedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
            Long ownerId,
            Instant cutoff,
            Pageable pageable
    );

    List<Beat> findByOwnerIdAndApprovedTrueAndRejectedFalseOrderByCreatedAtDesc(
            Long ownerId,
            Pageable pageable
    );

// --- POPULAR (owner-scoped) ---

    @Query("""
    SELECT b FROM Beat b
    WHERE b.owner.id = :ownerId
      AND b.approved = true AND b.rejected = false
      AND b.createdAt >= :cutoff
    ORDER BY (b.playCount + 3 * SIZE(b.upvotedBy)) DESC, b.createdAt DESC
""")
    List<Beat> findOwnerPopularSince(@Param("ownerId") Long ownerId,
                                     @Param("cutoff") Instant cutoff,
                                     Pageable pageable);

    @Query("""
    SELECT b FROM Beat b
    WHERE b.approved = true AND b.rejected = false
      AND b.createdAt >= :cutoff
    ORDER BY (b.playCount + 3 * SIZE(b.upvotedBy)) DESC, b.createdAt DESC
""")
    List<Beat> findGlobalPopularSince(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("""
SELECT b FROM Beat b
WHERE b.approved = true AND b.rejected = false
  AND b.createdAt >= :cutoff
ORDER BY b.createdAt DESC
""")
    Page<Beat> findNewBeatsSince(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("""
SELECT COUNT(b) FROM Beat b
WHERE b.approved = true AND b.rejected = false
  AND b.createdAt >= :cutoff
""")
    int countNewBeatsSince(@Param("cutoff") Instant cutoff);
// --- TRENDING pool (owner-scoped); we rank in service, like global ---

    @Query("""
    SELECT b FROM Beat b
    WHERE b.owner.id = :ownerId
      AND b.approved = true AND b.rejected = false
      AND b.createdAt >= :cutoff
""")
    List<Beat> findOwnerTrendingPool(@Param("ownerId") Long ownerId,
                                     @Param("cutoff") Instant cutoff,
                                     Pageable pageable);

// --- FEATURED active (owner-scoped), same ordering as global ---
@Query(value = """
    SELECT b.*
    FROM beat b
    JOIN promotion promo ON promo.target_id = b.id
    WHERE b.owner_id = :ownerId
      AND b.approved = true
      AND b.rejected = false
      AND promo.target_type = 'BEAT'
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
List<Beat> findActiveFeaturedByOwner(@Param("ownerId") Long ownerId,
                                     @Param("now") Instant now,
                                     Pageable pageable);

    long countByOwnerId(Long ownerId);


    @Query("select coalesce(sum(b.playCount), 0) from Beat b where b.owner.id = :ownerId")
    long sumPlayCountByOwnerId(@Param("ownerId") Long ownerId);

    boolean existsBySlug(String slug);

    Optional<Beat> findBySlug(String slug);

    @Query("select c from BeatComment c where c.beat.id = :beatId order by c.createdAt desc")
    List<BeatComment> findComments(@Param("beatId") Long beatId);

    @Query("select c from BeatComment c where c.id = :commentId and c.beat.id = :beatId")
    Optional<BeatComment> findComment(@Param("beatId") Long beatId, @Param("commentId") Long commentId);


    @Query("""
    select b from Beat b
    where b.isSample = false
      and b.approved = true
      and b.rejected = false
    order by b.createdAt desc
""")
    List<Beat> listRecent(Pageable pageable);

    @Query("""
    select b from Beat b
    where b.isSample = false
      and b.approved = true
      and b.rejected = false
      and (
           lower(coalesce(b.title, '')) like lower(concat('%', :q, '%'))
        or lower(coalesce(b.tags,  '')) like lower(concat('%', :q, '%'))
        or lower(coalesce(b.genre, '')) like lower(concat('%', :q, '%'))
      )
    order by b.createdAt desc
""")
    List<Beat> search(@Param("q") String q, Pageable pageable);

    @Query("""
    select b.id
    from Beat b
      join b.upvotedBy u
    where u.id = :userId
      and b.id in :beatIds
""")
    List<Long> findLikedBeatIds(@Param("userId") Long userId,
                                @Param("beatIds") List<Long> beatIds);


    @Query("""
        select distinct b
        from Beat b
        where b.owner.id = :ownerId
          and b.isSample = false
          and not exists (
              select 1
              from Pack p
              join p.beats pb
              where pb.id = b.id
          )
        order by b.createdAt desc
    """)
    List<Beat> findStandaloneByOwnerId(@Param("ownerId") Long ownerId);


    // BEAT
    @Query(
            value = """
        SELECT b FROM Beat b
        JOIN b.owner o
        WHERE b.approved = true AND (
            LOWER(COALESCE(b.title, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(b.tags, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(b.slug, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(b.artist, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(o.displayName, '')) LIKE concat('%', :qNormalized, '%')

            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(b.title, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(b.tags, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(b.slug, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(b.artist, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(o.displayName, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
        )
        ORDER BY b.createdAt DESC
    """,
            countQuery = """
        SELECT count(b) FROM Beat b
        JOIN b.owner o
        WHERE b.approved = true AND (
            LOWER(COALESCE(b.title, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(b.tags, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(b.slug, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(b.artist, '')) LIKE concat('%', :qNormalized, '%')
            OR LOWER(COALESCE(o.displayName, '')) LIKE concat('%', :qNormalized, '%')

            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(b.title, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(b.tags, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(b.slug, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(b.artist, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
            OR REPLACE(REPLACE(REPLACE(LOWER(COALESCE(o.displayName, '')), ' ', ''), '-', ''), '_', '') LIKE concat('%', :qNormalized, '%')
        )
    """
    )
    Page<Beat> searchFlexiblePaginated(@Param("qNormalized") String qNormalized, Pageable pageable);

    int countByOwnerIdAndCreatedAtAfter(Long ownerId, Instant after);

    List<Beat> findByApprovedTrueAndRejectedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
            Instant cutoff,
            Pageable pageable
    );

    // Count number of completed license purchases per beat
    @Query("SELECT bl.beat.id, COUNT(bl.id) FROM BeatLicense bl WHERE bl.beat.id IN :ids GROUP BY bl.beat.id")
    List<Object[]> countLicensesSoldForBeatsRaw(@Param("ids") List<Long> ids);

    // Sum total earnings per beat
    @Query("SELECT bl.beat.id, COALESCE(SUM(bl.price), 0) FROM BeatLicense bl WHERE bl.beat.id IN :ids GROUP BY bl.beat.id")
    List<Object[]> sumEarningsForBeatsRaw(@Param("ids") List<Long> ids);

    @Query("SELECT COUNT(b) FROM Beat b WHERE b.approved = true AND b.rejected = false AND b.createdAt > :since")
    int countNewBeats(@Param("since") Instant since);

    @Query("SELECT COUNT(b) FROM Beat b WHERE b.approved = true AND b.rejected = false")
    int countAllApproved();

    @Query("""
    SELECT COUNT(b) FROM Beat b
    WHERE b.approved = true AND b.rejected = false
      AND (
           LOWER(b.title) LIKE %:qLike% OR
           LOWER(b.slug) LIKE %:qLike% OR
           REPLACE(LOWER(b.slug), ' ', '') LIKE %:qNoSpace% OR
           LOWER(b.genre) LIKE %:qLike%
      )
""")
    int countSearchMatches(@Param("qLike") String qLike, @Param("qNoSpace") String qNoSpace);

    @Query("""
    SELECT b FROM Beat b
    WHERE b.approved = true AND b.rejected = false
    ORDER BY b.createdAt DESC
""")
    Page<Beat> listRecentPaginated(Pageable pageable);

}


