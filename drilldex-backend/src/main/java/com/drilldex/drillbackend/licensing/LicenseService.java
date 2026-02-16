package com.drilldex.drillbackend.licensing;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class LicenseService {

    private final Path licenseDir;

    public LicenseService(@Value("${app.licenses.dir:licenses}") String licenseDir) {
        this.licenseDir = Paths.get(licenseDir).toAbsolutePath().normalize();
    }

    /**
     * Get the license file from disk by filename
     */
    public File getLicenseFile(String filename) {
        // Prevent directory traversal
        if (filename.contains("..")) {
            throw new IllegalArgumentException("Invalid license filename");
        }

        return licenseDir.resolve(filename).toFile();
    }
}
