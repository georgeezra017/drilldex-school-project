package com.drilldex.drillbackend.purchase.dto;

import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.purchase.Purchase;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDto {
    private String orderId;
    private List<PurchaseDto> purchases;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;

    /**
     * Build OrderDto from a list of Purchase entities.
     * Requires repositories to resolve promotions.
     */
    public static OrderDto from(String orderId, List<Purchase> purchases,
                                BeatRepository beatRepo,
                                PackRepository packRepo,
                                KitRepository kitRepo) {

        List<PurchaseDto> purchaseDtos = purchases.stream()
                .map(p -> PurchaseDto.from(p, beatRepo, packRepo, kitRepo))
                .toList();

        BigDecimal subtotal = purchases.stream()
                .map(p -> p.getPricePaid() != null ? p.getPricePaid() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal tax = BigDecimal.ZERO; // adjust if you store tax
        BigDecimal total = subtotal.add(tax);

        return OrderDto.builder()
                .orderId(orderId)
                .purchases(purchaseDtos)
                .subtotal(subtotal)
                .tax(tax)
                .total(total)
                .build();
    }

    /**
     * Build OrderDto directly from DTOs (already mapped).
     * Useful if repositories are not available.
     */
    public static OrderDto fromDtos(String orderId, List<PurchaseDto> purchaseDtos) {

        BigDecimal subtotal = purchaseDtos.stream()
                .filter(p -> p.getType() != null &&
                        !p.getType().equals("subscription"))
                .map(p -> p.getPricePaid() != null ? p.getPricePaid() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal total = subtotal.add(tax);

        return OrderDto.builder()
                .orderId(orderId)
                .purchases(purchaseDtos)
                .subtotal(subtotal)
                .tax(tax)
                .total(total)
                .build();
    }
}