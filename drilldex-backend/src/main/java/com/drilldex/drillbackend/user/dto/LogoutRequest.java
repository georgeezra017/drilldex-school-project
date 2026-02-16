package com.drilldex.drillbackend.user.dto;

import lombok.Data;

@Data
public class LogoutRequest {
    private String refreshToken;
}