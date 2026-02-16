package com.drilldex.drillbackend.subscription;

import com.drilldex.drillbackend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    List<Subscription> findByUser(User user);
    Optional<Subscription> findFirstByUserAndStatusOrderByStartedAtDesc(User user, String status);
    Optional<Subscription> findByUserIdAndLastOrderId(Long userId, String lastOrderId);
    boolean existsByUserAndPriceCentsGreaterThan(User user, int price);


    @Query("""
    SELECT s 
    FROM Subscription s 
    WHERE s.user.id = :userId 
      AND s.status = 'active'
      AND (s.currentPeriodEnd IS NULL OR s.currentPeriodEnd > CURRENT_TIMESTAMP)
    ORDER BY s.startedAt DESC
""")
    List<Subscription> findActiveByUserId(@Param("userId") Long userId);

}
