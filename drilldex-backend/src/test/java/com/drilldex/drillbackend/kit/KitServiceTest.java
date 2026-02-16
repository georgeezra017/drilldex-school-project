package com.drilldex.drillbackend.kit;

import com.drilldex.drillbackend.kit.dto.KitUploadMeta;
import com.drilldex.drillbackend.preview.PreviewGenerator;
import com.drilldex.drillbackend.promotions.PromotionRepository;
import com.drilldex.drillbackend.purchase.PurchaseRepository;
import com.drilldex.drillbackend.storage.StorageService;
import com.drilldex.drillbackend.user.CurrentUserService;
import com.drilldex.drillbackend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KitServiceTest {

    private KitRepository kitRepository;
    private StorageService storageService;
    private KitService kitService;

    @BeforeEach
    void setUp() {
        kitRepository = mock(KitRepository.class);
        StorageService storage = mock(StorageService.class);
        KitFileRepository kitFileRepository = mock(KitFileRepository.class);
        PromotionRepository promotionRepository = mock(PromotionRepository.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        PurchaseRepository purchaseRepository = mock(PurchaseRepository.class);
        PreviewGenerator previewGenerator = mock(PreviewGenerator.class);

        kitService = new KitService(
                kitRepository,
                storage,
                kitFileRepository,
                promotionRepository,
                currentUserService,
                purchaseRepository,
                previewGenerator,
                kitRepository // used as `repo`
        );
    }

    @Test
    void shouldThrowIfMetaIsNull() {
        User user = new User();
        MultipartFile zip = mock(MultipartFile.class);

        assertThrows(RuntimeException.class, () -> {
            kitService.createKit(user, null, null, List.of(), zip);
        });
    }

    @Test
    void shouldThrowIfNameIsBlank() {
        User user = new User();
        MultipartFile zip = mock(MultipartFile.class);

        KitUploadMeta meta = new KitUploadMeta("  ", "Type", "Desc", "tag", BigDecimal.TEN, 80, 160, "Cmin");

        assertThrows(RuntimeException.class, () -> {
            kitService.createKit(user, meta, null, List.of(), zip);
        });
    }

    @Test
    void shouldThrowIfPriceIsNegative() {
        User user = new User();
        MultipartFile zip = mock(MultipartFile.class);

        KitUploadMeta meta = new KitUploadMeta("Valid", "Type", "Desc", "tag", new BigDecimal("-1"), 80, 160, "Cmin");

        assertThrows(RuntimeException.class, () -> {
            kitService.createKit(user, meta, null, List.of(), zip);
        });
    }

    @Test
    void shouldThrowIfZipIsNull() {
        User user = new User();
        KitUploadMeta meta = new KitUploadMeta("Valid", "Type", "Desc", "tag", BigDecimal.TEN, 80, 160, "Cmin");

        assertThrows(RuntimeException.class, () -> {
            kitService.createKit(user, meta, null, List.of(), null);
        });
    }

    @Test
    void shouldThrowIfZipIsEmpty() {
        User user = new User();
        MultipartFile zip = mock(MultipartFile.class);
        when(zip.isEmpty()).thenReturn(true);

        KitUploadMeta meta = new KitUploadMeta("Valid", "Type", "Desc", "tag", BigDecimal.TEN, 80, 160, "Cmin");

        assertThrows(RuntimeException.class, () -> {
            kitService.createKit(user, meta, null, List.of(), zip);
        });
    }

    // Additional tests could mock zip contents and assert Kit is saved

}
