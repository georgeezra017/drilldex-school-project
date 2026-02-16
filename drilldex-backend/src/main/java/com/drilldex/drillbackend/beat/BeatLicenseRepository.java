// src/main/java/com/drilldex/drillbackend/beat/BeatLicenseRepository.java
package com.drilldex.drillbackend.beat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BeatLicenseRepository extends JpaRepository<BeatLicense, Long> {
    List<BeatLicense> findByBeatIdAndEnabledTrue(Long beatId);
    Optional<BeatLicense> findByBeatIdAndTypeAndEnabledTrue(Long beatId, LicenseType type);





}