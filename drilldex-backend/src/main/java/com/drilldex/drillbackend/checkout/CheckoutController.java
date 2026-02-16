package com.drilldex.drillbackend.checkout;

import com.drilldex.drillbackend.auth.JwtService;
import com.drilldex.drillbackend.checkout.dto.CheckoutRequest;
import com.drilldex.drillbackend.checkout.dto.CheckoutResult;
import com.drilldex.drillbackend.checkout.dto.CheckoutSession;
import com.drilldex.drillbackend.checkout.dto.ConfirmCheckoutRequest;
import com.drilldex.drillbackend.user.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Date;

@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final com.drilldex.drillbackend.user.UserRepository userRepository;
    private final JwtService jwtService;

    @PostMapping("/start")
    public ResponseEntity<CheckoutSession> startCheckout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CheckoutRequest request,
            HttpServletRequest httpRequest
    ) {
        User user = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                Date expiration = claims.getExpiration();
                if (expiration != null && expiration.before(new Date())) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }

                String email = claims.getSubject();
                user = userRepository.findByEmail(email).orElse(null);
            } catch (Exception e) {
                System.out.println("[CHECKOUT] Invalid JWT: " + e.getMessage());
            }
        }

        // user can be null here for guest checkout
        CheckoutSession session = checkoutService.start(user, request, httpRequest);
        return ResponseEntity.ok(session);
    }

    @PostMapping("/confirm")
    public ResponseEntity<CheckoutResult> confirmCheckout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ConfirmCheckoutRequest request) {

        // Generate a unique order ID for this checkout
        String orderId = java.util.UUID.randomUUID().toString();
        System.out.println("[CHECKOUT] Generated orderId: " + orderId);

        User user = null;

        // Validate JWT if provided
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Claims claims = jwtService.parse(token); // throws if invalid
                Date expiration = claims.getExpiration();
                if (expiration != null && expiration.before(new Date())) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }

                String email = claims.getSubject();
                user = userRepository.findByEmail(email).orElse(null);
                if (user != null) {
                    System.out.println("[CHECKOUT] Authenticated user: " + user.getEmail());
                } else {
                    System.out.println("[CHECKOUT] JWT valid but user not found, treating as guest.");
                }
            } catch (Exception e) {
                System.out.println("[CHECKOUT] Invalid JWT: " + e.getMessage());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        } else {
            System.out.println("[CHECKOUT] No JWT header found, guest checkout.");
        }

        System.out.println("[CHECKOUT] Provider: " + request.getProvider());
        System.out.println("[CHECKOUT] Number of items: " + (request.getItems() != null ? request.getItems().size() : 0));

        try {
            // === LOCAL TEST PAYMENTS ONLY ===
            String provider = request.getProvider() != null ? request.getProvider() : "test";
            if (!"test".equalsIgnoreCase(provider)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(CheckoutResult.builder()
                                .success(false)
                                .message("Only local test payments are supported in the school build")
                                .orderId(orderId)
                                .build());
            }

            // Only after verification, proceed to confirm the order
            CheckoutResult result = checkoutService.confirm(user, request, orderId);
            System.out.println("[CHECKOUT] Checkout successful: " + result);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(CheckoutResult.builder()
                            .success(false)
                            .message("Checkout failed: " + e.getMessage())
                            .orderId(orderId)
                            .build());
        }
    }
}
