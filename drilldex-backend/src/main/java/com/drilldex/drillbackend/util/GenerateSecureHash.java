package com.drilldex.drillbackend.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GenerateSecureHash {
    public static void main(String[] args) {
        String rawPassword = "Drzy!Temp#2025Reset"; // strong temporary password
        String hash = new BCryptPasswordEncoder().encode(rawPassword);
        System.out.println(hash);
    }
}
