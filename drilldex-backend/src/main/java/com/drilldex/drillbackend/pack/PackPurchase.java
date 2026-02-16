//package com.drilldex.drillbackend.pack;
//
//import com.drilldex.drillbackend.pack.Pack;
//import com.drilldex.drillbackend.user.User;
//import jakarta.persistence.*;
//import lombok.Getter;
//import lombok.NoArgsConstructor;
//import lombok.Setter;
//
//import java.time.LocalDateTime;
//
//@Entity
//@Getter
//@Setter
//@NoArgsConstructor
//public class PackPurchase {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @ManyToOne(optional = false)
//    @JoinColumn(name = "buyer_id") // ✅ Resolves the ambiguity
//    private User buyer;
//
//    @ManyToOne(optional = false)
//    private Pack pack;
//
//    private LocalDateTime purchaseTime;
//
//    private String licenseFilePath;
//    private String zipDownloadPath;
//}