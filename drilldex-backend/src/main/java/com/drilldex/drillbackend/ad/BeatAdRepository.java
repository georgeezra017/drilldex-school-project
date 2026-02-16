package com.drilldex.drillbackend.ad;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BeatAdRepository extends JpaRepository<BeatAd, Long> {
    List<BeatAd> findByActiveTrue();
}