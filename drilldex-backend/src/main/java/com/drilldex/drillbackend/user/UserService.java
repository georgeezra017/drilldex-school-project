package com.drilldex.drillbackend.user;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.user.dto.RegisterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BeatRepository beatRepository;
    private final PackRepository packRepository;

    public User register(RegisterRequest request) {
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        user.setRole(Role.USER);

        user.setDisplayName(request.getDisplayName());

        Role requestedRole = request.getRole() != null ? request.getRole() : Role.USER;
        if (requestedRole == Role.ADMIN) {
            requestedRole = Role.USER;
        }
        user.setRole(requestedRole);



//        user.setRole(request.getRole());
        return userRepository.save(user);
    }

    public long getTotalTrackCount(Long userId) {
        long individualBeats = beatRepository.countByOwnerId(userId);
        long beatsInPacks = packRepository.countBeatsAcrossPacksByOwnerId(userId);
        return individualBeats + beatsInPacks;
    }

    public int countUniqueBeatsByProducer(Long userId) {
        // 1. Beats the user uploaded directly (approved and not rejected)
        Set<Long> beatIds = beatRepository.findByOwnerId(userId).stream()
                .filter(Beat::isApproved)
                .filter(b -> !b.isRejected())
                .map(Beat::getId)
                .collect(Collectors.toSet());

        // 2. Beats included in any approved, not rejected pack by this user
        List<Pack> packs = packRepository.findByOwnerIdAndApprovedTrueAndRejectedFalse(userId);
        for (Pack pack : packs) {
            if (pack.getBeats() != null) {
                pack.getBeats().stream()
                        .map(Beat::getId)
                        .forEach(beatIds::add); // deduplicate
            }
        }

        return beatIds.size();
    }

    public int countDeduplicatedBeats(Long userId) {
        Set<Long> beatIds = beatRepository.findByOwnerId(userId).stream()
                .filter(Beat::isApproved)
                .filter(b -> !b.isRejected())
                .map(Beat::getId)
                .collect(Collectors.toSet());

        List<Pack> packs = packRepository.findByOwnerIdAndApprovedTrueAndRejectedFalse(userId);
        for (Pack pack : packs) {
            if (pack.getBeats() != null) {
                pack.getBeats().stream()
                        .map(Beat::getId)
                        .forEach(beatIds::add);
            }
        }

        return beatIds.size();
    }

    private String generateReferralCode(String displayName) {
        // Remove spaces and non-alphanumeric characters
        String base = displayName.replaceAll("[^A-Za-z0-9]", "");
        // Append 3-digit random number
        int suffix = (int) (Math.random() * 900) + 100; // 100–999
        return base + suffix;
    }

    public User findOrCreateFromGoogle(String email, String googleId, String displayName, String profilePictureUrl) {
        return userRepository.findByEmail(email).map(existingUser -> {
            // Update Google ID if not already set
            if (existingUser.getGoogleId() == null) {
                existingUser.setGoogleId(googleId);
            }

            // Optionally update name and profile picture if blank
            if (existingUser.getDisplayName() == null || existingUser.getDisplayName().isBlank()) {
                existingUser.setDisplayName(displayName);
            }
            if (existingUser.getProfilePicturePath() == null || existingUser.getProfilePicturePath().isBlank()) {
                existingUser.setProfilePicturePath(profilePictureUrl);
            }

            return userRepository.save(existingUser);

        }).orElseGet(() -> {
            User newUser = new User();
            newUser.setEmail(email);
            newUser.setGoogleId(googleId);
            newUser.setDisplayName(displayName);
            newUser.setProfilePicturePath(profilePictureUrl);
            newUser.setRole(Role.ARTIST);
            newUser.setPassword(null); // Google OAuth users don’t have passwords

            // Set referral code
            newUser.setReferralCode(generateReferralCode(displayName));

            // Grant lifetime pro if within first 200 users
            long userCount = userRepository.count();
            if (userCount < 200) {
                newUser.setPlan("pro");
                newUser.setPlanBillingCycle("lifetime");
                newUser.setCurrentPeriodEnd(
                        LocalDateTime.of(2099, 12, 31, 0, 0)
                                .atZone(ZoneOffset.UTC)
                                .toInstant()
                );
            } else {
                newUser.setPlan("free");
            }

            newUser.setPromoCredits(BigDecimal.ZERO);
            newUser.setReferralCredits(BigDecimal.ZERO);

            return userRepository.save(newUser);
        });
    }


}