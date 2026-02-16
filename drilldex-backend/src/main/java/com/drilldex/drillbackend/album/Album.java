package com.drilldex.drillbackend.album;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Album {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String description;

    private String coverImagePath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    //    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL)
//    private List<Beat> beats;
    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Beat> beats;

    @Column(nullable = false)
    private boolean approved = false;

    @Column(nullable = false)
    private boolean rejected = false;
}