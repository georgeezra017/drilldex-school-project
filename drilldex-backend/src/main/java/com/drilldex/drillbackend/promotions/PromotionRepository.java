package com.drilldex.drillbackend.promotions;

import com.drilldex.drillbackend.user.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    List<Promotion> findByOwnerId(Long userId);
    List<Promotion> findByTargetTypeAndTargetId(Promotion.TargetType type, Long id);
    List<Promotion> findByStatus(String status);
    List<Promotion> findByTargetType(Promotion.TargetType targetType);

    default List<Promotion> findActiveByTargetType(Promotion.TargetType targetType) {
        return findByTargetType(targetType).stream()
                .filter(Promotion::isActive)
                .toList();
    }



    default List<Promotion> findAllActive() {
        return findAll().stream()
                .filter(Promotion::isActive)
                .toList();
    }

    @Query(value = """
    SELECT p.target_id FROM promotion p
    WHERE p.target_type = 'KIT'
      AND p.start_date <= :now
      AND p.start_date + make_interval(days => p.duration_days) > :now
    GROUP BY p.target_id
    ORDER BY MAX(p.start_date) DESC
    """, nativeQuery = true)
    List<Long> findActiveFeaturedKitIds(@Param("now") Instant now, Pageable pageable);

    @Query(value = """
    SELECT p.target_id FROM promotion p
    WHERE p.target_type = 'KIT'
      AND p.owner_id = :ownerId
      AND p.start_date <= :now
      AND p.start_date + make_interval(days => p.duration_days) > :now
    ORDER BY p.start_date DESC
    """, nativeQuery = true)
    List<Long> findActiveFeaturedKitIdsByOwner(@Param("ownerId") Long ownerId,
                                               @Param("now") Instant now,
                                               Pageable pageable);



    @Query(value = """
    SELECT EXISTS (
        SELECT 1 FROM promotion p
        WHERE p.target_type = :targetType
          AND p.target_id = :targetId
          AND p.start_date <= :now
          AND (p.start_date + (p.duration_days * interval '1 day')) > :now
    )
""", nativeQuery = true)
    boolean isCurrentlyFeatured(@Param("targetType") String targetType,
                                @Param("targetId") Long targetId,
                                @Param("now") Instant now);

    List<Promotion> findByTargetTypeAndStartDateBefore(Promotion.TargetType targetType, Instant now, Pageable pageable);

    @Query("SELECT p FROM Promotion p " +
            "WHERE p.targetType = :type AND p.owner.id = :ownerId")
    List<Promotion> findByTargetTypeAndOwnerId(@Param("type") Promotion.TargetType type,
                                               @Param("ownerId") Long ownerId);

    @Query(value = """
    SELECT *
    FROM promotion p
    WHERE p.target_type = :type
      AND p.start_date <= :now
      AND p.start_date + make_interval(days => p.duration_days) > :now
    ORDER BY
      CASE lower(p.tier)
        WHEN 'spotlight' THEN 3
        WHEN 'premium'   THEN 2
        ELSE 1
      END DESC,
      p.start_date DESC
    """,
            nativeQuery = true)
    List<Promotion> findActiveByTypeOrdered(@Param("type") String type,
                                            @Param("now")  java.time.Instant now,
                                            org.springframework.data.domain.Pageable pageable);

    List<Promotion> findByOwner(User user);

    @Query(value = """
    SELECT COUNT(*)
    FROM promotion p
    WHERE p.target_type = :type
      AND p.start_date <= :now
      AND (p.status IS NULL OR LOWER(p.status) <> 'canceled')
      AND p.start_date + (p.duration_days * INTERVAL '1 day') > :now
""", nativeQuery = true)
    int countActiveByType(@Param("type") String type, @Param("now") Instant now);
}
