package com.drilldex.drillbackend.user;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Data
@Table(name = "referral_events")
public class ReferralEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private User referrer;          // the user who earned credits

    @ManyToOne
    private User referred;          // the new user who signed up

    private BigDecimal amount;      // credits awarded

    private Instant createdAt = Instant.now(); // timestamp of the referral
}