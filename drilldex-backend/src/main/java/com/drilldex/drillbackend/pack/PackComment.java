package com.drilldex.drillbackend.pack;

import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter @Setter @NoArgsConstructor
public class PackComment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "pack_id", nullable = false)
    private Pack pack;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 2000)
    private String text;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /* Optional: likes for comments (so you can /like & /unlike like beats API draft) */
    @ManyToMany
    @JoinTable(
            name = "pack_comment_likes",
            joinColumns = @JoinColumn(name = "comment_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private Set<User> likedBy = new HashSet<>();

    public int getLikeCount() {
        return likedBy == null ? 0 : likedBy.size();
    }

    public boolean toggleLike(User u) {
        if (likedBy.contains(u)) {
            likedBy.remove(u);
            return false;
        } else {
            likedBy.add(u);
            return true;
        }
    }
}