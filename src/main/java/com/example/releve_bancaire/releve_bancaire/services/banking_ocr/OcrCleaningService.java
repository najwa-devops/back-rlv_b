package com.example.releve_bancaire.releve_bancaire.services.banking_ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ✅ SERVICE NETTOYAGE OCR - VERSION FINALE
 *
 * Patterns RIB :
 * - France (Société Générale): 23 digits (format 5-5-11-2)
 * - Maroc (Attijariwafa): 24 digits (format 3-3-16-2 OU 3-3-2-16-2)
 */
@Service
@Slf4j
public class OcrCleaningService {

    // ✅ PATTERNS RIB UNIVERSELS
    private static final Pattern[] RIB_PATTERNS = {
            // France: 30003 01234 00012345678 12 (5-5-11-2 = 23 digits)
            Pattern.compile("\\b(\\d{5})\\s+(\\d{5})\\s+(\\d{11})\\s+(\\d{2})\\b"),

            // Maroc format 1: 007 450 00 15208000000293 44 (3-3-2-14-2 = 24 digits)
            Pattern.compile("\\b(\\d{3})\\s+(\\d{3})\\s+(\\d{2})\\s+(\\d{14})\\s+(\\d{2})\\b"),

            // Maroc format 2: 007 450 15208000000293 44 (3-3-16-2 = 24 digits)
            Pattern.compile("\\b(\\d{3})\\s+(\\d{3})\\s+(\\d{16})\\s+(\\d{2})\\b"),

            // Sans espaces: 23 digits continus
            Pattern.compile("\\b(\\d{23})\\b"),

            // Sans espaces: 24 digits continus
            Pattern.compile("\\b(\\d{24})\\b")
    };

    public String cleanOcrText(String rawText) {
        if (rawText == null)
            return "";

        String cleaned = rawText;

        // Normaliser retours à la ligne
        cleaned = cleaned.replaceAll("\\r\\n", "\n");
        cleaned = cleaned.replaceAll("\\r", "\n");

        // Supprimer lignes vides multiples
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");

        log.debug("Texte nettoyé: {} caractères", cleaned.length());
        return cleaned;
    }

    /**
     * ✅ EXTRACTION RIB - Supporte France ET Maroc
     */
    public String extractRib(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Essayer chaque pattern
        for (Pattern pattern : RIB_PATTERNS) {
            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) {
                String rib = buildRibFromGroups(matcher);

                // Validation longueur
                if (rib.length() == 23 || rib.length() == 24) {
                    log.info("✅ RIB extrait: {}**** ({} digits)",
                            rib.substring(0, Math.min(6, rib.length())),
                            rib.length());
                    return rib;
                }
            }
        }

        log.warn("⚠️ Aucun RIB valide trouvé");
        return null;
    }

    /**
     * Construire RIB à partir des groupes capturés
     */
    private String buildRibFromGroups(Matcher matcher) {
        StringBuilder rib = new StringBuilder();

        for (int i = 1; i <= matcher.groupCount(); i++) {
            String group = matcher.group(i);
            if (group != null) {
                rib.append(group);
            }
        }

        return rib.toString();
    }

    /**
     * ✅ NORMALISATION DATE
     */
    public String normalizeDate(String dateStr) {
        if (dateStr == null)
            return null;

        // Remplacer espaces et tirets par /
        String normalized = dateStr
                .replaceAll("\\s+", "/")
                .replaceAll("-", "/");

        // Si année sur 2 chiffres, compléter
        String[] parts = normalized.split("/");
        if (parts.length == 3 && parts[2].length() == 2) {
            int year = Integer.parseInt(parts[2]);
            // Si < 50 → 20XX, sinon 19XX
            int fullYear = (year < 50) ? (2000 + year) : (1900 + year);
            normalized = parts[0] + "/" + parts[1] + "/" + fullYear;
        }

        return normalized;
    }

    /**
     * ✅ NORMALISATION MONTANT
     */
    public String normalizeAmount(String amountStr) {
        if (amountStr == null)
            return "0";
        String raw = amountStr.trim().replaceAll("\\s+", "");
        if (raw.isEmpty()) {
            return "0";
        }

        int lastComma = raw.lastIndexOf(',');
        int lastDot = raw.lastIndexOf('.');
        int decimalIdx = Math.max(lastComma, lastDot);

        String sign = raw.startsWith("-") ? "-" : "";
        String digitsOnly;
        String decimals = "";

        if (decimalIdx > 0 && decimalIdx < raw.length() - 1) {
            String intPart = raw.substring(0, decimalIdx).replaceAll("[^0-9]", "");
            decimals = raw.substring(decimalIdx + 1).replaceAll("[^0-9]", "");
            digitsOnly = intPart.isEmpty() ? "0" : intPart;
        } else {
            digitsOnly = raw.replaceAll("[^0-9]", "");
            if (digitsOnly.isEmpty()) {
                digitsOnly = "0";
            }
        }

        if (!decimals.isEmpty()) {
            return sign + digitsOnly + "." + decimals;
        }
        return sign + digitsOnly;
    }

    /**
     * ✅ DÉTECTION DEVISE
     */
    public String detectCurrency(String text) {
        String upperText = text.toUpperCase();

        if (upperText.contains("DIRHAM") || upperText.contains("DH")) {
            return "MAD";
        }
        if (upperText.contains("EURO") || upperText.contains("EUR") || upperText.contains("€")) {
            return "EUR";
        }
        if (upperText.contains("DOLLAR") || upperText.contains("USD") || upperText.contains("$")) {
            return "USD";
        }

        return "MAD"; // Défaut Maroc
    }
}
