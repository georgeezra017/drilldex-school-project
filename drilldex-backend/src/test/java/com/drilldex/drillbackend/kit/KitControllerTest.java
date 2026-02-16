package com.drilldex.drillbackend.kit;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.kit.dto.KitUploadMeta;
import com.drilldex.drillbackend.notification.NotificationRepository;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.user.ReferralEventRepository;
import com.drilldex.drillbackend.user.Role;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KitControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ReferralEventRepository referralEventRepository;
    @Autowired private KitRepository kitRepository;
    @Autowired private NotificationRepository notificationRepository;

    @Transactional
    @BeforeEach
    void cleanTestUser() {
        userRepository.findByEmail("kituser@drilldex.io").ifPresent(user -> {
            referralEventRepository.deleteAll();
            kitRepository.deleteAllByOwnerId(user.getId()); // ✅ safely delete dependent kits
            notificationRepository.deleteAllByRecipient_Id(user.getId());
            userRepository.delete(user);
        });
    }

    private byte[] createFakeZipWithMp3() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addFakeMp3(zos, "kick.wav");
            addFakeMp3(zos, "snare.wav");
        }
        return baos.toByteArray();
    }

    private void addFakeMp3(ZipOutputStream zos, String filename) throws IOException {
        zos.putNextEntry(new ZipEntry(filename));
        byte[] header = {
                (byte) 0xFF, (byte) 0xFB, (byte) 0x50, (byte) 0x80,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
        };
        for (int i = 0; i < 500; i++) {
            zos.write(header);
        }
        zos.closeEntry();
    }

    @Test
    void testCreateKit_successfulUpload() throws Exception {
        // 🧪 Create and save test user
        User user = new User();
        user.setEmail("kituser@drilldex.io");
        user.setDisplayName("Kit User");
        user.setPassword("secret");
        user.setRole(Role.ARTIST);
        user = userRepository.save(user);

        CustomUserDetails principal = new CustomUserDetails(user);

        // 🧪 Prepare mock multipart files
        MockMultipartFile zip = new MockMultipartFile("zip", "kit.zip", "application/zip", createFakeZipWithMp3());
        MockMultipartFile cover = new MockMultipartFile("cover", "cover.jpg", MediaType.IMAGE_JPEG_VALUE, "fake-image".getBytes());

        KitUploadMeta meta = new KitUploadMeta(
                "Drill Kit",
                "Drum Kit",
                "Hard-hitting sounds",
                "drill,808",
                new BigDecimal("20.00"),
                120,
                140,
                "A#"
        );

        MockMultipartFile metaPart = new MockMultipartFile(
                "meta", "meta.json", "application/json", objectMapper.writeValueAsBytes(meta)
        );

        mockMvc.perform(multipart("/api/kits/upload")
                        .file(zip)
                        .file(cover)
                        .file(metaPart)
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
                        ))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                )
                .andExpect(status().isCreated()); // ← update if controller returns 201
    }
}