package com.drilldex.drillbackend.token;

import com.drilldex.drillbackend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);
    void deleteByUser(User user);

    @Transactional
    void deleteByToken(String token);
}