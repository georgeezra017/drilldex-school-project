// src/main/java/com/drilldex/drillbackend/purchase/dto/BuyBeatRequest.java
package com.drilldex.drillbackend.purchase.dto;

import com.drilldex.drillbackend.beat.LicenseType;

public record BuyBeatRequest(Long beatId, LicenseType licenseType) {}