package com.drilldex.drillbackend.user.dto;

public record UserDto(
        Long id,
        String displayName,
        String email,
        String role
) {}