// src/test/java/com/drilldex/drillbackend/util/TestCleanupService.java
package com.drilldex.drillbackend.util;

import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.user.ReferralEventRepository;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class TestCleanupService {

    private final UserRepository userRepository;
    private final PackRepository packRepository;
    private final BeatRepository beatRepository;
    private final ReferralEventRepository referralEventRepository;

    public TestCleanupService(
            UserRepository userRepository,
            PackRepository packRepository,
            BeatRepository beatRepository,
            ReferralEventRepository referralEventRepository
    ) {
        this.userRepository = userRepository;
        this.packRepository = packRepository;
        this.beatRepository = beatRepository;
        this.referralEventRepository = referralEventRepository;
    }

    @Transactional
    public void cleanUserByEmail(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            referralEventRepository.deleteAll();                  // related
            beatRepository.deleteAllByOwnerId(user.getId());      // beats
            packRepository.deleteAllByOwnerId(user.getId());      // packs
            userRepository.delete(user);                          // user
        });
    }
}