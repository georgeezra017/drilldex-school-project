package com.drilldex.drillbackend.auth;

import com.drilldex.drillbackend.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenExpirationMs;

    // If your secret in properties is RAW text (what you have now):
    public JwtService(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.access-token.expiration-ms}") long accessTokenExpirationMs
    ) {
        // Use ONE of these:
        // 1) RAW UTF‑8 secret (your current properties example):
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        // 2) If you later switch to base64 secret in properties, use this instead:
        // this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));

        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    public String generateToken(User user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(user.getEmail())
                .addClaims(Map.of("role", user.getRole().name()))
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + accessTokenExpirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractUsername(String token) {
        return parse(token).getSubject();
    }

    public String extractRole(String token) {
        return parse(token).get("role", String.class);
    }

    public boolean isTokenValid(String token, org.springframework.security.core.userdetails.UserDetails userDetails) {
        return userDetails.getUsername().equals(extractUsername(token));
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .setAllowedClockSkewSeconds(5)
                .build()
                .parseClaimsJws(token)

                .getBody();
    }

}