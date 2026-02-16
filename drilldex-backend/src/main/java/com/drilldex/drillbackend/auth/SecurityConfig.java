package com.drilldex.drillbackend.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.http.HttpMethod;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // Add this if missing
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationProvider authenticationProvider
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .exceptionHandling(ex -> ex
                        // 401 for unauthenticated (e.g., expired/missing token)
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // 403 for authenticated but insufficient role
                        .accessDeniedHandler(new AccessDeniedHandlerImpl())
                )
                .authorizeHttpRequests(auth -> auth
                                .requestMatchers(HttpMethod.GET,
                                        // Packs
                                        "/api/packs/approved",
                                        "/api/packs/featured",
                                        "/api/packs/popular",
                                        "/api/packs/trending",
                                        "/api/packs/new",
                                        "/api/packs/search",
                                        "/api/packs/by-slug/**",
                                        "/api/packs/*/licenses",
                                        "/api/packs/*/comments",
                                        "/api/packs/*/preview-playlist",
                                        "/api/packs/*/contents",

                                        // Kits
                                        "/api/kits/approved",
                                        "/api/kits/featured",
                                        "/api/kits/popular",
                                        "/api/kits/trending",
                                        "/api/kits/new",
                                        "/api/kits/search",
                                        "/api/kits/by-slug/**",
                                        "/api/kits/*/comments",
                                        "/api/kits/*/preview-playlist",
                                        "/api/kits/*/contents",

                                        // Beats
                                        "/api/beats/approved",
                                        "/api/beats/featured",
                                        "/api/beats/popular",
                                        "/api/beats/trending",
                                        "/api/beats/new",
                                        "/api/beats/search",
                                        "/api/beats/filter",
                                        "/api/beats/*/preview-url",
                                        "/api/beats/styles/**",
                                        "/api/beats/by-slug/**",
                                        "/api/beats/*/licenses",
                                        "/api/beats/*/comments",

                                        // Track pages
                                        "/api/track/**",

                                        // Users
                                        "/api/users",
                                        "/api/users/producers",
                                        "/api/users/{id}",
                                        "/api/users/{id}/beats",
                                        "/api/users/{id}/beats/featured",
                                        "/api/users/{id}/beats/new",
                                        "/api/users/{id}/beats/popular",
                                        "/api/users/{id}/beats/trending",
                                        "/api/users/{id}/packs/featured",
                                        "/api/users/{id}/packs/new",
                                        "/api/users/{id}/packs/popular",
                                        "/api/users/{id}/packs/trending",
                                        "/api/users/{id}/kits/featured",
                                        "/api/users/{id}/kits/new",
                                        "/api/users/{id}/kits/popular",
                                        "/api/users/{id}/kits/trending",
                                        "/api/users/{id}/beats/approved",
                                        "/api/users/{id}/packs/approved",
                                        "/api/users/{id}/kits/approved",

                                        // Static uploads
                                        "/uploads/**"
                                ).permitAll()
                                .requestMatchers("/api/checkout/start").permitAll()
                                .requestMatchers("/api/checkout/confirm").permitAll()
                                .requestMatchers("/api/licenses/**").permitAll()
                                .requestMatchers(
                                        "/api/auth/register",
                                        "/api/auth/login",
                                        "/api/auth/logout",
                                        "/api/auth/refresh-token",
                                        "/api/beats/sample/**",
                                        "/api/feed",
                                        "/api/auth/google/callback",
                                        "/api/beats/search",
                                        "/api/beats/filter",
                                        "/api/ads/promoted",
                                        "/api/beats/featured",
                                        "/api/beats/new",
                                        "/api/beats/popular",
//                                "/api/auth/me",
                                        "/api/beats/approved",
                                        "/api/packs/approved",
                                        "/api/kits/approved",
                                        "/api/track/**",
                                        "/api/beats/styles/**",
                                        "/api/beats/featured/**",
                                        "/api/beats/new/**",
                                        "/api/beats/popular/**",
                                        "/api/beats/search/**",
                                        "/api/beats/*/preview-url",
                                        "/api/packs/**",
                                        "/api/kits/**",
                                        "/api/subscriptions/order/**"

                                ).permitAll()
                                .requestMatchers(HttpMethod.PATCH, "/api/me/email").permitAll()
                                .requestMatchers("/api/me/**").authenticated()
                                .requestMatchers("/api/billing/subscriptions/**").authenticated()
                                .requestMatchers("/api/auth/me").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/chat/threads").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/chat/history").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/chat/stream").permitAll()
                                .requestMatchers("/api/notifications/stream").permitAll()
                                // Notifications enforce auth in controller to avoid JWT filter edge cases
                                .requestMatchers("/api/notifications/**").permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/chat/send").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/follow/*/is-following").permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/follow/*").authenticated()
                                .requestMatchers(HttpMethod.DELETE, "/api/follow/*").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/packs/**").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/beats/*/licenses").permitAll()
                                .requestMatchers("/api/purchases/**").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/purchases/packs/**").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/purchases/kits/**").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/purchases/beats/**").authenticated()
                                .requestMatchers("/api/packs/upload").hasAnyRole("ARTIST", "USER", "ADMIN")
                                .requestMatchers("/api/packs/**").authenticated()
                                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                                .requestMatchers("/api/admin/packs/**").hasRole("ADMIN")
                                .requestMatchers("/uploads/**").permitAll()
                                .requestMatchers("/api/beats/upload").hasAnyRole("ARTIST", "USER", "ADMIN")
                                .requestMatchers(HttpMethod.POST, "/api/packs/buy/**").authenticated() // buying a pack
                                .requestMatchers("/api/beats/download/**").authenticated()
                                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                                .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().requestMatchers("/uploads/**", "/licenses/**");
    }

    @Bean
    public AuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }
}
