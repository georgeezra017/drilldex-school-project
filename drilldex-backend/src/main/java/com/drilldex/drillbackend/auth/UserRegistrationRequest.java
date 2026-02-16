package com.drilldex.drillbackend.auth;

import lombok.Data;

@Data
public class UserRegistrationRequest {
    private String email;
    private String password;
    private String displayName;
    private String role; // e.g., "ARTIST", "USER", "ADMIN"
}