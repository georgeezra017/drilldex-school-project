package com.drilldex.drillbackend.config;

import com.drilldex.drillbackend.storage.StorageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class TestStorageConfig {

    @Bean
    @Primary // ✅ Ensures this replaces StorageService in test context
    public StorageService storageService() {
        return new StorageService() {
            private final ConcurrentHashMap<String, byte[]> memoryStore = new ConcurrentHashMap<>();

            @Override
            public String save(MultipartFile file, String folder) throws IOException {
                String key = folder + "/" + UUID.randomUUID();
                memoryStore.put(key, file.getBytes());
                return "test-storage/" + key;
            }

            @Override
            public String save(InputStream in, long size, String originalFilename, String folder, String contentType) throws IOException {
                String key = folder + "/" + UUID.randomUUID();
                byte[] bytes = in.readAllBytes();
                memoryStore.put(key, bytes);
                return "test-storage/" + key;
            }

            @Override
            public void delete(String path) {
                memoryStore.remove(path);
            }

            // no load() override — your StorageService doesn't define it
        };
    }
}
