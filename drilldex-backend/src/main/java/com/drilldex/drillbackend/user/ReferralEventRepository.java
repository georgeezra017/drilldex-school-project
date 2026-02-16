package com.drilldex.drillbackend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

public interface ReferralEventRepository extends JpaRepository<ReferralEvent, Long> {

    @Query("""
        SELECT COALESCE(SUM(r.amount), 0) 
        FROM ReferralEvent r 
        WHERE r.referrer.id = :userId 
          AND r.createdAt >= :startOfDay
          AND r.createdAt <= :endOfDay
    """)
    BigDecimal sumCreditsForUserToday(@Param("userId") Long userId,
                                      @Param("startOfDay") Instant startOfDay,
                                      @Param("endOfDay") Instant endOfDay);
}