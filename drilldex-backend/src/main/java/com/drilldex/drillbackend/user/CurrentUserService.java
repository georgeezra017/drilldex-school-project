package com.drilldex.drillbackend.user;// src/main/java/com/drilldex/drillbackend/security/CurrentUserService.java

import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentUserService {
    private final UserRepository userRepository;

    public User getCurrentUserOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new RuntimeException("Unauthenticated");
        }
        // Assuming username == email (most setups do this)
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("Current user not found"));
    }

    public User getCurrentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || !auth.isAuthenticated()) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).orElse(null);
    }
}