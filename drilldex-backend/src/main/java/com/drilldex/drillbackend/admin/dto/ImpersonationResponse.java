package com.drilldex.drillbackend.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ImpersonationResponse {
    private String accessToken;
    private String refreshToken;
    private Long userId;
    private String displayName;
    private String avatarUrl;
    private String role;
}