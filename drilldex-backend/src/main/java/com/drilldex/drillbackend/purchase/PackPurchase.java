package com.drilldex.drillbackend.purchase;

import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
public class PackPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "buyer_id") // ✅ Resolves the ambiguity
    private User buyer;

    @ManyToOne(optional = false)
    private Pack pack;

    private LocalDateTime purchaseTime;

    private String licenseFilePath;

    private String zipDownloadPath;

    // Getters & setters

    public Long getId() {
        return id;
    }

    public User getBuyer() {
        return buyer;
    }

    public void setBuyer(User buyer) {
        this.buyer = buyer;
    }

    public Pack getPack() {
        return pack;
    }

    public void setPack(Pack pack) {
        this.pack = pack;
    }

    public LocalDateTime getPurchaseTime() {
        return purchaseTime;
    }

    public void setPurchaseTime(LocalDateTime purchaseTime) {
        this.purchaseTime = purchaseTime;
    }

    public String getLicenseFilePath() {
        return licenseFilePath;
    }

    public void setLicenseFilePath(String licenseFilePath) {
        this.licenseFilePath = licenseFilePath;
    }

    public String getZipDownloadPath() {
        return zipDownloadPath;
    }

    public void setZipDownloadPath(String zipDownloadPath) {
        this.zipDownloadPath = zipDownloadPath;
    }
}