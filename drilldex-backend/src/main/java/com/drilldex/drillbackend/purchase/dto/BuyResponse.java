// src/main/java/com/drilldex/drillbackend/purchase/dto/BuyResponse.java
package com.drilldex.drillbackend.purchase.dto;

public record BuyResponse(
        Long purchaseId,
        String message,
        String licenseUrl
) {}