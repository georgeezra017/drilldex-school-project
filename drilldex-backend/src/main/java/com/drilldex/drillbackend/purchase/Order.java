package com.drilldex.drillbackend.purchase;

import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String orderId; // UUID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user; // can be null for guest orders

    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}