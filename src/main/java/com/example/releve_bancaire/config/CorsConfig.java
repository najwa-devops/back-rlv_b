package com.example.releve_bancaire.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@Slf4j
public class CorsConfig {

    @Value("${cors.allowed.origins:http://localhost:3000,http://localhost:3001,http://localhost:3022}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        // With credentials=true, wildcard origins are invalid in allowedOrigins.
        if (origins.contains("*")) {
            log.warn("cors.allowed.origins contains '*': using allowedOriginPatterns instead.");
            config.setAllowedOriginPatterns(origins);
        } else {
            config.setAllowedOrigins(origins);
        }

        // Autoriser toutes les methodes HTTP
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"));

        // Autoriser tous les headers
        config.setAllowedHeaders(Arrays.asList("*"));

        // Permettre les credentials
        config.setAllowCredentials(true);

        // Exposition des headers
        config.setExposedHeaders(Arrays.asList(
                "Content-Disposition",
                "Content-Type",
                "Content-Length"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
