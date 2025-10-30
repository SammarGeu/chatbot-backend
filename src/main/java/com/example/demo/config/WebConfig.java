package com.example.demo.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String path = Paths.get(uploadDir).toAbsolutePath().toUri().toString(); // file:/...
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(path)
                .setCachePeriod(3600);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // allow the streaming endpoint to be called directly from the Angular dev server
        registry.addMapping("/api/v1/chat/stream")
                .allowedOrigins("http://localhost:4200")
                .allowedMethods("GET")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);

        // if you already had other mappings, keep them here — this just adds the stream mapping
    }
}
