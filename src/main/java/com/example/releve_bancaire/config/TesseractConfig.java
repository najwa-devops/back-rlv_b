package com.example.releve_bancaire.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Slf4j
@Configuration
@Profile("legacy-tesseract")
public class TesseractConfig {

    @Value("${tesseract.datapath}")
    private String tesseractDataPath;

    @Value("${tesseract.language}")
    private String tesseractLanguage;

    @PostConstruct
    public void init() {
        // Set the system property that Tesseract native library looks for
        System.setProperty("TESSDATA_PREFIX", tesseractDataPath);
        log.info("=== TESSERACT CONFIGURATION ===");
        log.info("TESSDATA_PREFIX set to: {}", tesseractDataPath);
        log.info("Tesseract language configured: {}", tesseractLanguage);
        log.info("===============================");
    }

    @Bean
    public ITesseract tesseract() {
        Tesseract tesseract = new Tesseract();

        // Explicitly set the data path
        tesseract.setDatapath(tesseractDataPath);

        // Set language (eng+fra for English and French)
        tesseract.setLanguage(tesseractLanguage);

        // Configure Tesseract settings
        tesseract.setPageSegMode(3); // AUTO - Fully automatic page segmentation
        tesseract.setOcrEngineMode(3); // Default, based on what is available

        log.info("Tesseract bean configured successfully");
        log.info("  - Data path: {}", tesseractDataPath);
        log.info("  - Language: {}", tesseractLanguage);
        log.info("  - Page Seg Mode: 3 (AUTO)");
        log.info("  - OCR Engine Mode: 3 (DEFAULT)");

        return tesseract;
    }

}
