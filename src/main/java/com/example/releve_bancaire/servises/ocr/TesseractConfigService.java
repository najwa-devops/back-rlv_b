package com.example.releve_bancaire.servises.ocr;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
@Slf4j
public class TesseractConfigService {

    @Value("${tesseract.path:/usr/bin/tesseract}")
    private String tesseractPath;

    @Value("${tesseract.datapath:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessDataPath;

    @Value("${tesseract.language:fra+eng}")
    private String language;

    @Value("${tesseract.fast-image-language:fra}")
    private String fastImageLanguage;

    public Tesseract createConfiguredInstance(int attemptNumber) {
        Tesseract tesseract = new Tesseract();
        String resolvedDatapath = resolveDatapath();
        String resolvedLanguage = resolveLanguage(resolvedDatapath, language);

        tesseract.setDatapath(resolvedDatapath);
        tesseract.setLanguage(resolvedLanguage);

        if (attemptNumber <= 0) {
            configureDefault(tesseract);
            log.info("Config Tesseract default: lang={}, datapath={}, PSM=6, OEM=1, DPI=300",
                    resolvedLanguage, resolvedDatapath);
        } else {
            configureFallback(tesseract);
            log.info("Config Tesseract fallback: lang={}, datapath={}, PSM=3, OEM=1, DPI=300",
                    resolvedLanguage, resolvedDatapath);
        }

        return tesseract;
    }

    public Tesseract createFastImageInstance(int attemptNumber) {
        Tesseract tesseract = new Tesseract();
        String resolvedDatapath = resolveDatapath();
        String resolvedLanguage = resolveLanguage(resolvedDatapath, fastImageLanguage);

        tesseract.setDatapath(resolvedDatapath);
        tesseract.setLanguage(resolvedLanguage);

        if (attemptNumber <= 0) {
            configureFastImageDefault(tesseract);
            log.info("Config Tesseract image-fast: lang={}, datapath={}, PSM=11, OEM=1, DPI=220",
                    resolvedLanguage, resolvedDatapath);
        } else {
            configureFastImageFallback(tesseract);
            log.info("Config Tesseract image-fast fallback: lang={}, datapath={}, PSM=6, OEM=1, DPI=220",
                    resolvedLanguage, resolvedDatapath);
        }

        return tesseract;
    }

    private String resolveDatapath() {
        if (isValidTessdataDir(tessDataPath)) {
            return tessDataPath;
        }

        List<String> candidates = List.of(
                "C:\\Program Files\\Tesseract-OCR\\tessdata",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tessdata",
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata");

        for (String candidate : candidates) {
            if (isValidTessdataDir(candidate)) {
                log.warn("Configured tessdata path not found: {}. Falling back to {}", tessDataPath, candidate);
                return candidate;
            }
        }

        log.warn("No valid tessdata directory found. OCR may fail. configured={}", tessDataPath);
        return tessDataPath;
    }

    private String resolveLanguage(String datapath, String preferred) {
        if (datapath == null || datapath.isBlank()) {
            return preferred;
        }

        String[] tokens = preferred != null ? preferred.split("\\+") : new String[0];
        StringBuilder available = new StringBuilder();
        for (String token : tokens) {
            String lang = token.trim();
            if (lang.isBlank()) {
                continue;
            }
            if (Files.exists(Path.of(datapath, lang + ".traineddata"))) {
                if (available.length() > 0) {
                    available.append('+');
                }
                available.append(lang);
            }
        }

        if (available.length() > 0) {
            return available.toString();
        }

        if (Files.exists(Path.of(datapath, "eng.traineddata"))) {
            log.warn("Preferred OCR language '{}' not found in {}. Falling back to 'eng'", preferred, datapath);
            return "eng";
        }
        if (Files.exists(Path.of(datapath, "fra.traineddata"))) {
            log.warn("Preferred OCR language '{}' not found in {}. Falling back to 'fra'", preferred, datapath);
            return "fra";
        }

        return preferred;
    }

    private boolean isValidTessdataDir(String directory) {
        if (directory == null || directory.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(directory);
            return Files.exists(path) && Files.isDirectory(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void configureDefault(Tesseract tesseract) {
        tesseract.setPageSegMode(6);
        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "1");
        tesseract.setTessVariable("user_defined_dpi", "300");
        tesseract.setTessVariable("textord_heavy_nr", "1");
    }

    private void configureFallback(Tesseract tesseract) {
        tesseract.setPageSegMode(3);
        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "1");
        tesseract.setTessVariable("user_defined_dpi", "300");
        tesseract.setTessVariable("textord_heavy_nr", "1");
    }

    private void configureFastImageDefault(Tesseract tesseract) {
        tesseract.setPageSegMode(11);
        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "0");
        tesseract.setTessVariable("user_defined_dpi", "220");
        tesseract.setTessVariable("textord_heavy_nr", "0");
        tesseract.setTessVariable("load_system_dawg", "0");
        tesseract.setTessVariable("load_freq_dawg", "0");
    }

    private void configureFastImageFallback(Tesseract tesseract) {
        tesseract.setPageSegMode(6);
        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "0");
        tesseract.setTessVariable("user_defined_dpi", "220");
        tesseract.setTessVariable("textord_heavy_nr", "0");
        tesseract.setTessVariable("load_system_dawg", "0");
        tesseract.setTessVariable("load_freq_dawg", "0");
    }
}
