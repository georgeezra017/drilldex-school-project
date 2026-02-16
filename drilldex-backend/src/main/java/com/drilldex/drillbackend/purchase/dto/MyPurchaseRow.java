package com.drilldex.drillbackend.purchase.dto;

import java.math.BigDecimal;

public record MyPurchaseRow(
        Long purchaseId,
        String type,
        String title,
        String img,
        String licenseUrl,
        String audioUrl,
        String zipUrl,
        String purchasedAtIso,
        BigDecimal pricePaid,
        String currency,
        Long sourceId
) {}