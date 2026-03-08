package com.example.releve_bancaire.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import nu.pattern.OpenCV;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class OpenCvConfig {

    @PostConstruct
    public void loadOpenCvNativeLibrary() {
        try {
            OpenCV.loadLocally();
            log.info("OpenCV chargé avec succès");
        } catch (Exception e) {
            log.error("Échec chargement OpenCV: {}", e.getMessage(), e);
            throw new RuntimeException("OpenCV non disponible", e);
        }
    }
}
