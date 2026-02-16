package com.drilldex.drillbackend.pack;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.pack.dto.PackUploadMeta;
import com.drilldex.drillbackend.pack.dto.PackUploadMeta.LicenseLine;
import com.drilldex.drillbackend.user.ReferralEventRepository;
import com.drilldex.drillbackend.user.Role;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import com.drilldex.drillbackend.util.TestCleanupService;
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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class PackControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ReferralEventRepository referralEventRepository;
    @Autowired private BeatRepository beatRepository;
    @Autowired private PackRepository packRepository;
    @Autowired private TestCleanupService testCleanupService;

    @BeforeEach
    void cleanTestUser() {
        testCleanupService.cleanUserByEmail("test@drilldex.io");
    }

    private byte[] createFakeZipWithMp3() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addFakeMp3(zos, "sound1.mp3");
            addFakeMp3(zos, "sound2.mp3");
        }
        return baos.toByteArray();
    }

    private void addFakeMp3(ZipOutputStream zos, String filename) throws IOException {
        zos.putNextEntry(new ZipEntry(filename));
        byte[] header = {
                (byte) 0xFF, (byte) 0xFB, (byte) 0x50, (byte) 0x80,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
        };
        for (int i = 0; i < 1000; i++) {
            zos.write(header);
        }
        zos.closeEntry();
    }

    @Test
    void shouldUploadPackSuccessfully() throws Exception {
        User user = new User();
        user.setEmail("test@drilldex.io");
        user.setDisplayName("Test User");
        user.setPassword("secret");
        user.setRole(Role.ARTIST);
        user = userRepository.save(user);

        CustomUserDetails principal = new CustomUserDetails(user);

        MockMultipartFile zip = new MockMultipartFile(
                "zip", "pack.zip", "application/zip", createFakeZipWithMp3()
        );

        MockMultipartFile cover = new MockMultipartFile(
                "cover", "cover.jpg", MediaType.IMAGE_JPEG_VALUE, "fake image".getBytes()
        );

        PackUploadMeta meta = new PackUploadMeta(
                "Test Pack",
                "Test pack description",
                "trap, drill",
                BigDecimal.valueOf(12.99),
                List.of(), // beatIds
                List.of(new LicenseLine("MP3", true, new BigDecimal("9.99")))
        );

        MockMultipartFile metaPart = new MockMultipartFile(
                "meta", "meta.json", "application/json", objectMapper.writeValueAsBytes(meta)
        );

        mockMvc.perform(multipart("/api/packs/upload")
                        .file(zip)
                        .file(cover)
                        .file(metaPart)
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
                        ))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                )
                .andExpect(status().isCreated());
    }
}