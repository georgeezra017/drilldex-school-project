package com.drilldex.drillbackend.beat;

import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter; import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Getter @Setter @NoArgsConstructor
public class BeatComment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Beat beat;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(nullable = false, length = 1000)
    private String text;

    @CreationTimestamp
    private Instant createdAt;
}