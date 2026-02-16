// src/main/java/com/drilldex/drillbackend/config/StaticResourceConfig.java
package com.drilldex.drillbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {


    @Value("${app.upload.root}")
    private String uploadRoot;

    @Override public void addResourceHandlers(ResourceHandlerRegistry r) {
        String base = System.getProperty("user.dir").replace("\\","/");
        r.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + base + "/uploads/");
    }
}