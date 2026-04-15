package com.tc.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(trimmedAllowedOrigins())
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }

    private String[] trimmedAllowedOrigins() {
        String[] trimmed = new String[allowedOrigins.length];
        for (int i = 0; i < allowedOrigins.length; i++) {
            trimmed[i] = allowedOrigins[i].trim();
        }
        return trimmed;
    }
}
