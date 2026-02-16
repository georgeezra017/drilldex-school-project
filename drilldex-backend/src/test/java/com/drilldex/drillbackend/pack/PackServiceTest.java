package com.drilldex.drillbackend.pack;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.pack.dto.PackUploadMeta;
import com.drilldex.drillbackend.pack.dto.PackUploadMeta.LicenseLine;
import com.drilldex.drillbackend.storage.StorageService;
import com.drilldex.drillbackend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PackServiceTest {

    @Mock
    private PackRepository packRepo;

    @Mock
    private BeatRepository beatRepo;

    @Mock
    private StorageService storage;

    @InjectMocks
    private PackService packService;

    private User user;
    private PackUploadMeta meta;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        user = new User();
        user.setId(1L);

        meta = new PackUploadMeta(
                "Test Pack",
                "Test description",
                "dark,fast",
                new BigDecimal("9.99"),
                Collections.emptyList(),
                List.of(new LicenseLine("BASIC", true, new BigDecimal("9.99")))
        );
    }

    @Test
    void shouldThrowIfMetaIsNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            packService.uploadPackMixed(user, null, null, null, null, null);
        });
    }

    @Test
    void shouldThrowIfTitleMissing() {
        PackUploadMeta badMeta = new PackUploadMeta(
                null,
                "desc",
                "tags",
                new BigDecimal("10.00"),
                null,
                null
        );

        Exception e = assertThrows(IllegalArgumentException.class, () -> {
            packService.uploadPackMixed(user, badMeta, null, null, null, null);
        });

        assertEquals("Pack name is required", e.getMessage());
    }

    @Test
    void shouldThrowIfNoAudioFiles() {
        PackUploadMeta noBeatsMeta = new PackUploadMeta(
                "My Pack",
                "desc",
                "tags",
                new BigDecimal("10.00"),
                Collections.emptyList(),
                Collections.emptyList()
        );

        Exception e = assertThrows(IllegalArgumentException.class, () -> {
            packService.uploadPackMixed(user, noBeatsMeta, null, Collections.emptyList(), null, null);
        });

        assertEquals("No valid audio files were ingested.", e.getMessage());
    }

    @Test
    void shouldSavePackWithExistingBeats() throws Exception {
        Beat mockBeat = new Beat();
        mockBeat.setId(100L);
        mockBeat.setTitle("included beat");

        when(beatRepo.findAllById(List.of(100L))).thenReturn(List.of(mockBeat));
        when(packRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PackUploadMeta withBeat = new PackUploadMeta(
                "With Beats",
                "desc",
                "tags",
                new BigDecimal("5.00"),
                List.of(100L),
                null
        );

        Pack result = packService.uploadPackMixed(user, withBeat, null, null, null, null);

        assertNotNull(result);
        assertEquals("With Beats", result.getTitle());
        assertEquals(1, result.getBeats().size());
        verify(packRepo).save(any());
    }
}