package com.drilldex.drillbackend.auth;

import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.token.RefreshToken;
import com.drilldex.drillbackend.token.RefreshTokenService;
import com.drilldex.drillbackend.user.*;
import com.drilldex.drillbackend.user.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final NotificationService notificationService;
    private final ReferralEventRepository referralEventRepository;



    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestBody RegisterRequest request,
            Authentication authentication
    ) {
        String referralCode = request.getReferralCode(); // <--- use this
        System.out.println("Referral code used: " + referralCode);
        // --- Check email uniqueness ---
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body("❌ Email is already taken.");
        }


        Role requestedRole = request.getRole() != null ? request.getRole() : Role.ARTIST;

        // --- Restrict ADMIN creation ---
        if (requestedRole == Role.ADMIN) {
            if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails adminDetails)) {
                return ResponseEntity
                        .status(HttpStatus.FORBIDDEN)
                        .body("⛔ Only admins can create other admins.");
            }

            User requester = adminDetails.getUser();
            if (requester.getRole() != Role.ADMIN) {
                return ResponseEntity
                        .status(HttpStatus.FORBIDDEN)
                        .body("⛔ Only admins can create other admins.");
            }
        }

        // --- 1️⃣ Create new user ---
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setRole(requestedRole);


        long totalUsers = userRepository.count();
        if (totalUsers < 200) {
            user.setPlan("pro");
            user.setPlanBillingCycle("lifetime"); // Optional: custom label for clarity
            user.setSubscriptionStart(Instant.now());
            user.setCurrentPeriodEnd(Instant.parse("2099-12-31T00:00:00Z")); // Far future = lifetime
        } else {
            user.setPlan("free");
        }

        user.setPromoCredits(BigDecimal.ZERO);
        user.setReferralCredits(BigDecimal.ZERO);
        user.setReferralCode(generateReferralCode(user.getDisplayName()));

        userRepository.save(user);

        // --- 2️⃣ Award referral credits if a referral code was used ---
        if (referralCode != null) {

            userRepository.findByReferralCode(referralCode).ifPresent(referrer -> {

                BigDecimal creditsPerReferral = switch (referrer.getPlan().toLowerCase()) {
                    case "free" -> BigDecimal.valueOf(5);
                    case "growth" -> BigDecimal.valueOf(10);
                    case "pro" -> BigDecimal.valueOf(20);
                    default -> BigDecimal.ZERO;
                };

                // Daily cap logic for free users
                if ("free".equalsIgnoreCase(referrer.getPlan())) {
                    Instant startOfDay = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
                    Instant endOfDay = startOfDay.plusSeconds(86399); // end of day
                    BigDecimal earnedToday = referralEventRepository.sumCreditsForUserToday(
                            referrer.getId(), startOfDay, endOfDay
                    );
                    BigDecimal dailyCap = BigDecimal.valueOf(20);
                    BigDecimal remaining = dailyCap.subtract(earnedToday);

                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        creditsPerReferral = BigDecimal.ZERO;
                    } else if (creditsPerReferral.compareTo(remaining) > 0) {
                        creditsPerReferral = remaining;
                    }
                }

                if (creditsPerReferral.compareTo(BigDecimal.ZERO) > 0) {
                    referrer.addReferralCredits(creditsPerReferral);
                    userRepository.save(referrer);

                    // Save referral event
                    ReferralEvent event = new ReferralEvent();
                    event.setReferrer(referrer);
                    event.setReferred(user);
                    event.setAmount(creditsPerReferral);
                    referralEventRepository.save(event);

                    // Send notification
                    Notification n = notificationService.create(
                            referrer,
                            NotificationType.SYSTEM,
                            RelatedType.USER,
                            user.getId(),
                            "Referral Bonus Earned!",
                            "You earned $" + creditsPerReferral + " in promo credits because " +
                                    user.getDisplayName() + " signed up with your referral link."
                    );
                    notificationService.pushNotificationToUser(referrer.getId(), n);
                }
            });
        }

        // --- 4️⃣ Generate tokens for new user ---
        String accessToken = jwtService.generateToken(user);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

        // --- 5️⃣ Return tokens ---
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken.getToken()
        ));
    }

    private String generateReferralCode(String displayName) {
        // Remove spaces and non-alphanumeric characters
        String base = displayName.replaceAll("[^A-Za-z0-9]", "");
        // Append 3-digit random number
        int suffix = (int) (Math.random() * 900) + 100; // 100–999
        return base + suffix;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        if (user.isBanned()) {
            throw new RuntimeException("This account has been banned.");
        }

        String accessToken = jwtService.generateToken(user);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);


        return new LoginResponse(accessToken, refreshToken.getToken());
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        String requestToken = request.getRefreshToken();
        var opt = refreshTokenService.findByToken(requestToken);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid refresh token"));
        }
        RefreshToken rt = opt.get();
        if (refreshTokenService.isExpired(rt)) {
            refreshTokenService.deleteByToken(requestToken);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token expired"));
        }
        String accessToken = jwtService.generateToken(rt.getUser());
        // keep same refresh token
        return ResponseEntity.ok(new LoginResponse(accessToken, requestToken));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var principal = (UserDetails) authentication.getPrincipal();

        String username = principal.getUsername();

        var dbUser = userRepository.findByEmail(username) // or findByUsername(username)
                .orElse(null);
        if (dbUser == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", dbUser.getId());
        userInfo.put("username", principal.getUsername ());
        userInfo.put("roles", principal.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .toList());

        return ResponseEntity.ok(userInfo);
    }

}
