package com.drilldex.drillbackend.user.dto;

import com.drilldex.drillbackend.user.Role;

// user/dto/UserResponse.java
public class UserResponse {
    public Long id;
    public String email;
    public String displayName;
    public String profilePicturePath;
    public Role role;
}