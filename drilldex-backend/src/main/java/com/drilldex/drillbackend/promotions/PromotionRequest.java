package com.drilldex.drillbackend.promotions;

public class PromotionRequest {

    private String targetType;    // "BEAT", "PACK", "KIT"
    private Long targetId;        // ID of the beat, pack, or kit
    private String tier;          // "standard", "premium", "spotlight"
    private int days;             // duration in days
    private String paymentMethod; // "test" (local simulation)
    private String orderId;       // internal order UUID

    public PromotionRequest() {
        // Default constructor for Jackson
    }

    public PromotionRequest(String targetType, Long targetId, String tier, int days) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.tier = tier;
        this.days = days;
    }

    public PromotionRequest(String targetType, Long targetId, String tier, int days, String paymentMethod) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.tier = tier;
        this.days = days;
        this.paymentMethod = paymentMethod;
    }

    // --- Getters & Setters ---
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
}
