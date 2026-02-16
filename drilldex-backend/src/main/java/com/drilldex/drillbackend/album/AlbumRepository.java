package com.drilldex.drillbackend.album;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlbumRepository extends JpaRepository<Album, Long> {
    List<Album> findByApprovedFalseAndRejectedFalse();

    @Query("SELECT a FROM Album a LEFT JOIN FETCH a.beats WHERE a.id = :albumId")
    Optional<Album> findByIdWithBeats(@Param("albumId") Long albumId);
}

