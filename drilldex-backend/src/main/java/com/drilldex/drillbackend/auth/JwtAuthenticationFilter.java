package com.drilldex.drillbackend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        final String path = request.getRequestURI();

        // 1) Skip CORS preflight and the refresh endpoint
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/api/auth/refresh-token".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        final String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("❌ Missing or malformed Authorization header");
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        String email = null;
        String role  = null;
        try {
            // 2) Try to read claims. If expired/invalid, don't authenticate and continue.
            email = jwtService.extractUsername(jwt);
            role  = jwtService.extractRole(jwt);
            log.debug("🔑 JWT extracted");
            log.debug("📧 Email extracted: {}", email);
            log.debug("🎭 Role extracted: {}", role);
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            log.debug("⌛ Access token expired. Letting request continue to trigger 401 -> refresh.");
            filterChain.doFilter(request, response);
            return;
        } catch (Exception ex) {
            log.debug("❌ Failed to parse JWT: {}", ex.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);
            if (jwtService.isTokenValid(jwt, userDetails)) {
                log.debug("✅ Token is valid for user: {}", email);

                // Build authorities from role claim (prefix with ROLE_)
                SimpleGrantedAuthority authority =
                        new SimpleGrantedAuthority("ROLE_" + (role != null ? role : "USER"));

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(userDetails, null, List.of(authority));
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authToken);
                log.debug("🔒 Security context updated with {}", authority.getAuthority());
            } else {
                log.warn("❌ Invalid token for user: {}", email);
            }
        } else {
            log.debug("ℹ️ No authentication set or email was null");
        }

        filterChain.doFilter(request, response);
    }
}