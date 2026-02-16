package com.drilldex.drillbackend.kit;

import jakarta.persistence.*;

@Entity
@Table(name = "kit_files")
public class KitFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kit_id", nullable = false)
    private Kit kit;

    @Column(nullable = false)
    private String path;

    // getters and setters
    public Long getId() { return id; }
    public Kit getKit() { return kit; }
    public void setKit(Kit kit) { this.kit = kit; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}