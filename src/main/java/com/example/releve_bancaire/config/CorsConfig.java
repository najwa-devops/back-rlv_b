package com.example.releve_bancaire.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {

        CorsConfiguration config = new CorsConfiguration();

        // Allow all origins
        config.addAllowedOriginPattern("*");

        // Allow all headers
        config.addAllowedHeader("*");

        // Allow all HTTP methods
        config.addAllowedMethod("*");

        // Allow credentials (cookies, authorization headers)
        config.setAllowCredentials(true);

        // Expose headers to the client
        config.addExposedHeader("Content-Disposition");
        config.addExposedHeader("Content-Type");
        config.addExposedHeader("Content-Length");
        config.addExposedHeader("Authorization");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Apply this configuration to all routes
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}