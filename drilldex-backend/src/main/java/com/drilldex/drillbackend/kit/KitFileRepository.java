package com.drilldex.drillbackend.kit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KitFileRepository extends JpaRepository<KitFile, Long> {

    @Query("select kf.path from KitFile kf where kf.kit.id = :kitId")
    List<String> findPathsByKitId(Long kitId);
}