package com.example.releve_bancaire.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Autoriser toutes les origines (en dev)
        config.setAllowedOrigins(Arrays.asList("http://localhost:3000", "http://localhost:3001",
                "http://127.0.0.1:3000", "http://172.20.1.3", "http://172.20.1.3:3000"));

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
