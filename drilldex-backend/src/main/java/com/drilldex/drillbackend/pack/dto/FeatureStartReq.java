package com.drilldex.drillbackend.pack.dto;

public record FeatureStartReq(String tier, Integer days) {
    public String tierOrDefault() {
        String t = (tier == null ? "" : tier.trim().toLowerCase());
        return switch (t) {
            case "premium", "spotlight" -> t;
            default -> "standard";
        };
    }
    public int daysOrDefault() {
        int d = (days == null ? 0 : days);
        if (d < 1) d = 7;
        return Math.min(d, 90);
    }
}