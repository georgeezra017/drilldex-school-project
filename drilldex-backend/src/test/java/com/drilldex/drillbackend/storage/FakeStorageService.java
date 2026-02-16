package com.drilldex.drillbackend.storage;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FakeStorageService implements StorageService {

    private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public String save(MultipartFile file, String folder) throws IOException {
        String key = folder + "/" + UUID.randomUUID();
        store.put(key, file.getBytes());
        return key;
    }

    @Override
    public String save(InputStream in, long size, String originalFilename, String folder, String contentType) throws IOException {
        String key = folder + "/" + UUID.randomUUID();
        byte[] bytes = in.readAllBytes();
        store.put(key, bytes);
        return key;
    }

    @Override
    public void delete(String keyOrUrl) {
        store.remove(keyOrUrl);
    }

    public boolean exists(String key) {
        return store.containsKey(key);
    }

    public void clear() {
        store.clear();
    }

    // No `load()` implementation needed for your tests
}