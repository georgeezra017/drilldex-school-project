package com.drilldex.drillbackend.checkout.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class CheckoutRequest {
    private List<CartItem> items;
    private String method; // "test" (local simulation)
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;

    @Data
    public static class CartItem {
        private String type; // beat | pack | kit | promotion | subscription
        private Long beatId;
        private Long packId;
        private Long kitId;
        private String licenseType;
        private String tier;
        private int days;
        private String planId;
        private String billingCycle;
        private BigDecimal price;
        private String subscriptionId;
        private String paymentMethodId;
    }
}
