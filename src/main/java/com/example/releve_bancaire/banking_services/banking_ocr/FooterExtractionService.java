package com.example.releve_bancaire.banking_services.banking_ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class FooterExtractionService {

    // Extrait toutes les informations du footer

    public Map<String, String> extractFooterInfo(String fullText) {
        Map<String, String> footerData = new HashMap<>();

        if (fullText == null || fullText.length() < 200) {
            return footerData;
        }

        // Définir le footer (derniers 40% du texte)
        int footerStartIndex = (int) (fullText.length() * 0.6);
        String footer = fullText.substring(footerStartIndex);

        log.info("=== ANALYSE FOOTER ===");
        log.info("Taille footer: {} caractères", footer.length());
        log.info("Contenu footer:\n{}", footer.substring(0, Math.min(footer.length(), 300)));

        // Extraire ICE avec plusieurs stratégies
        String ice = extractIce(footer);
        if (ice != null) {
            footerData.put("ice", ice);
            log.info("ICE extrait du footer: {}", ice);
        }

        // Extraire autres informations
        extractAllFooterFields(footer, footerData);

        return footerData;
    }

    private String extractIce(String footer) {
        // Stratégies multiples pour ICE

        // 1. Format standard "ICE: 123456789012345"
        Pattern icePattern1 = Pattern.compile(
                "(?i)(?:ice|i\\.\\s*c\\.\\s*e\\.?)\\s*[:.]?\\s*([0-9]{3}\\s*[0-9]{3}\\s*[0-9]{3}\\s*[0-9]{3}\\s*[0-9]{3}|[0-9]{15})");

        // 2. Format dans un bloc d'information
        Pattern icePattern2 = Pattern.compile(
                "(?i)(?:identifiant\\s+commun|n°\\s*ice)\\s*[:.]?\\s*([0-9]{15})");

        // 3. Juste 15 chiffres (fallback)
        Pattern icePattern3 = Pattern.compile("\\b([0-9]{15})\\b");

        // Essayer chaque pattern
        for (Pattern pattern : new Pattern[] { icePattern1, icePattern2, icePattern3 }) {
            Matcher matcher = pattern.matcher(footer);
            if (matcher.find()) {
                String found = matcher.group(1).replaceAll("\\s", "");
                if (found.matches("[0-9]{15}")) {
                    return found;
                }
            }
        }

        return null;
    }

    private void extractAllFooterFields(String footer, Map<String, String> data) {
        // Définir tous les patterns pour le footer
        Map<String, Pattern> footerPatterns = new HashMap<>();

        footerPatterns.put("ifNumber", Pattern.compile(
                "(?i)(?:i\\.?\\s*f\\.?|identifiant\\s+fiscal|n°\\s*if)\\s*[:.]?\\s*([0-9]{8,15})"));

        footerPatterns.put("patente", Pattern.compile(
                "(?i)(?:pat(?:ente)?|n°\\s*pat(?:ente)?)\\s*[:.]?\\s*(\\d+)"));

        footerPatterns.put("cnss", Pattern.compile(
                "(?i)(?:cnss|n°\\s*cnss)\\s*[:.]?\\s*([A-Z0-9/\\-]+)"));

        footerPatterns.put("rcNumber", Pattern.compile(
                "(?i)(?:r\\.?\\s*c\\.?|registre\\s+(?:de\\s+)?commerce|rc\\s+n[°o]?)\\s*[:.]?\\s*(\\d{1,10})"));

        // Appliquer chaque pattern
        for (Map.Entry<String, Pattern> entry : footerPatterns.entrySet()) {
            Matcher matcher = entry.getValue().matcher(footer);
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                data.put(entry.getKey(), value);
                log.info("Footer - {}: {}", entry.getKey(), value);
            }
        }
    }
}
