package com.drilldex.drillbackend.ad;

import com.drilldex.drillbackend.beat.Beat;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class BeatAd {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "beat_id", nullable = false)
    private Beat beat;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Long startTimeMillis;

    @Column(nullable = false)
    private Long endTimeMillis;
}