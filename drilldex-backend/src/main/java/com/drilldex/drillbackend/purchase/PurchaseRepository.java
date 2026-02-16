// src/main/java/com/drilldex/drillbackend/purchase/PurchaseRepository.java
package com.drilldex.drillbackend.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
    List<Purchase> findByBuyerId(Long buyerId);

    @Query("""
    SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
    FROM Purchase p
    WHERE p.promotionTargetType = :targetType
      AND p.promotionTargetId = :targetId
      AND p.purchasedAt BETWEEN :startDate AND :endDate
""")
    boolean existsByPromotionTarget(
            @Param("targetType") String targetType, // "BEAT", "PACK", "KIT"
            @Param("targetId") Long targetId,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate
    );

    List<Purchase> findAllByBuyerIdOrderByPurchasedAtDesc(Long buyerId);

    @Query("SELECT COUNT(p), COALESCE(SUM(p.pricePaid), 0) FROM Purchase p WHERE p.beat.id = :beatId")
    Object[] getBeatSalesAndEarnings(@Param("beatId") Long beatId);

    @Query("SELECT COUNT(p), COALESCE(SUM(p.pricePaid), 0) FROM Purchase p WHERE p.beat.id = :beatId")
    List<Object[]> getBeatSalesAndEarningsForProfile(@Param("beatId") Long beatId);

    @Query("SELECT COUNT(p), COALESCE(SUM(p.pricePaid), 0) FROM Purchase p WHERE p.pack.id = :packId")
    List<Object[]> getPackSalesAndEarnings(@Param("packId") Long packId);

    // ✅ Kit sales & earnings
    @Query("SELECT COUNT(p), COALESCE(SUM(p.pricePaid), 0) FROM Purchase p WHERE p.kit.id = :kitId")
    Object[] getKitSalesAndEarnings(@Param("kitId") Long kitId);

    @Query("""
    SELECT p.kit.id, COUNT(p), COALESCE(SUM(p.pricePaid), 0)
    FROM Purchase p
    WHERE p.kit IS NOT NULL AND p.kit.owner.id = :ownerId
    GROUP BY p.kit.id
""")
    List<Object[]> getKitSalesAndEarningsByOwner(@Param("ownerId") Long ownerId);

    List<Purchase> findAllByOrderId(String orderId);


}
