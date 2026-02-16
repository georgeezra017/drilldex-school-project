package com.drilldex.drillbackend;

import com.drilldex.drillbackend.user.Role;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.drilldex")
public class DrilldexBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrilldexBackendApplication.class, args);
    }
    // Create admin user on startup
    @Bean
    public CommandLineRunner createAdmin(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            String adminEmail = "admin@drilldex.com";
            if (!userRepository.existsByEmail(adminEmail)) {
                User admin = new User();
                admin.setEmail(adminEmail);
                admin.setPassword(passwordEncoder.encode("password123")); // 🔒 change this in production
                admin.setRole(Role.ADMIN);
                admin.setDisplayName("Admin");

                userRepository.save(admin);
                System.out.println("✅ Admin user created: " + adminEmail + " / password123");
            } else {
                System.out.println("ℹ️ Admin user already exists.");
            }
        };
    }
}
