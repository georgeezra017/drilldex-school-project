package com.drilldex.drillbackend.ad;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Ad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User advertiser;

    @ManyToOne(optional = false)
    private Beat beat;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private LocalDateTime endTime;

    @Column(nullable = false)
    private boolean approved = false;

    @Column(nullable = false)
    private boolean paid = false;

    private int impressions = 0; // Optional: track how many times this ad was shown
}