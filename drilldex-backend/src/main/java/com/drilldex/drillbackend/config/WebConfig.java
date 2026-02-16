package com.drilldex.drillbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

// src/main/java/com/drilldex/drillbackend/config/WebConfig.java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Value("${app.upload.root:uploads}") String uploadRoot;
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + Path.of(uploadRoot).toAbsolutePath().normalize().toString() + "/");
    }
}