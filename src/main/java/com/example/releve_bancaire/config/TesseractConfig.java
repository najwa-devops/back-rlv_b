package com.example.releve_bancaire.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
public class TesseractConfig {

    @Value("${tesseract.datapath}")
    private String tesseractDataPath;

    @Value("${tesseract.language}")
    private String tesseractLanguage;

    private String resolvedTessdataPath;
    private String resolvedLanguage;

    @PostConstruct
    public void init() {
        resolvedTessdataPath = resolveTessdataPath(tesseractDataPath);
        resolvedLanguage = resolveLanguage(resolvedTessdataPath, tesseractLanguage);
        System.setProperty("TESSDATA_PREFIX", resolvedTessdataPath);
        log.info("=== TESSERACT CONFIGURATION ===");
        log.info("TESSDATA_PREFIX set to: {}", resolvedTessdataPath);
        log.info("Tesseract language configured: {} (resolved: {})", tesseractLanguage, resolvedLanguage);
        log.info("===============================");
    }

    @Bean
    public ITesseract tesseract() {
        Tesseract tesseract = new Tesseract();

        tesseract.setDatapath(resolvedTessdataPath);

        // Set language (eng+fra for English and French)
        tesseract.setLanguage(resolvedLanguage);

        // Configure Tesseract settings
        tesseract.setPageSegMode(1); // AUTO_OSD - Automatic page segmentation with orientation and script detection
        tesseract.setOcrEngineMode(3); // Default, based on what is available

        // ✅ OPTIMISATION POUR RELEVÉS BANCAIRES
        tesseract.setTessVariable("preserve_interword_spaces", "1");

        log.info("Tesseract bean configured successfully");
        log.info("  - Data path: {}", resolvedTessdataPath);
        log.info("  - Language: {} (resolved from {})", resolvedLanguage, tesseractLanguage);
        log.info("  - Page Seg Mode: 1 (AUTO_OSD)");
        log.info("  - OCR Engine Mode: 3 (DEFAULT)");
        log.info("  - Preserve interword spaces: 1");

        return tesseract;
    }

    private String resolveTessdataPath(String configuredPath) {
        List<String> candidates = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isBlank()) {
            candidates.add(configuredPath.trim());
        }
        String envPrefix = System.getenv("TESSDATA_PREFIX");
        if (envPrefix != null && !envPrefix.isBlank()) {
            candidates.add(envPrefix.trim());
        }

        // Windows common locations
        candidates.add("C:/Program Files/Tesseract-OCR/tessdata");
        candidates.add("C:/Program Files (x86)/Tesseract-OCR/tessdata");

        // Linux common locations
        candidates.add("/usr/share/tesseract-ocr/5/tessdata");
        candidates.add("/usr/share/tesseract-ocr/4.00/tessdata");
        candidates.add("/usr/share/tessdata");

        for (String candidate : candidates) {
            if (hasRequiredLangData(candidate, tesseractLanguage)) {
                return candidate;
            }
        }

        String fallback = candidates.isEmpty() ? "" : candidates.get(0);
        log.warn("Aucun chemin tessdata valide trouvé pour les langues '{}'. Chemin utilisé: {}",
                tesseractLanguage, fallback);
        return fallback;
    }

    private boolean hasRequiredLangData(String tessdataPath, String languages) {
        try {
            if (tessdataPath == null || tessdataPath.isBlank()) {
                return false;
            }
            Path dir = Paths.get(tessdataPath);
            if (!Files.isDirectory(dir)) {
                return false;
            }
            String[] langs = (languages == null || languages.isBlank() ? "eng" : languages).split("\\+");
            for (String lang : langs) {
                String trimmed = lang.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!Files.exists(dir.resolve(trimmed + ".traineddata"))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveLanguage(String tessdataPath, String requestedLanguages) {
        String requested = (requestedLanguages == null || requestedLanguages.isBlank()) ? "eng" : requestedLanguages;
        Path dir;
        try {
            dir = Paths.get(tessdataPath);
        } catch (Exception e) {
            return requested;
        }

        List<String> available = new ArrayList<>();
        for (String lang : requested.split("\\+")) {
            String l = lang.trim();
            if (l.isEmpty()) {
                continue;
            }
            if (Files.exists(dir.resolve(l + ".traineddata"))) {
                available.add(l);
            }
        }
        if (!available.isEmpty()) {
            return String.join("+", available);
        }

        if (Files.exists(dir.resolve("eng.traineddata"))) {
            return "eng";
        }
        return requested;
    }

}
