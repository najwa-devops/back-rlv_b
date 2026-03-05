package com.example.releve_bancaire.servises.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SERVICE DE POST-TRAITEMENT OCR
 * 
 * Nettoie et corrige le texte OCR pour améliorer la précision
 * - Correction erreurs OCR communes (O→0, l→1, S→5)
 * - Normalisation dates
 * - Validation montants
 * - Nettoyage caractères parasites
 */
@Service
@Slf4j
public class OcrPostProcessor {

    // Mapping des erreurs OCR communes
    private static final Map<String, String> OCR_ERROR_CORRECTIONS = new HashMap<>();

    static {
        // Corrections pour les chiffres
        OCR_ERROR_CORRECTIONS.put("O", "0"); // O majuscule → 0
        OCR_ERROR_CORRECTIONS.put("o", "0"); // o minuscule → 0
        OCR_ERROR_CORRECTIONS.put("l", "1"); // l minuscule → 1
        OCR_ERROR_CORRECTIONS.put("I", "1"); // I majuscule → 1
        OCR_ERROR_CORRECTIONS.put("S", "5"); // S → 5
        OCR_ERROR_CORRECTIONS.put("s", "5"); // s → 5
        OCR_ERROR_CORRECTIONS.put("Z", "2"); // Z → 2
        OCR_ERROR_CORRECTIONS.put("B", "8"); // B → 8
    }

    /**
     * Post-traitement complet du texte OCR
     */
    public String postProcess(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return ocrText;
        }

        log.debug("Post-traitement OCR: {} caractères", ocrText.length());

        String processed = ocrText;

        // 1. Nettoyer caractères parasites
        processed = cleanGarbage(processed);

        // 2. Corriger montants
        processed = correctAmounts(processed);

        // 3. Normaliser dates
        processed = normalizeDates(processed);

        // 4. Corriger ICE/IF/RC
        processed = correctLegalNumbers(processed);

        log.debug("Post-traitement terminé: {} caractères", processed.length());

        return processed;
    }

    /**
     * Nettoie les caractères parasites et artefacts OCR
     */
    private String cleanGarbage(String text) {
        // Supprimer caractères de contrôle sauf \n, \r, \t
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        // Supprimer espaces multiples
        text = text.replaceAll(" {2,}", " ");

        // Supprimer lignes vides multiples
        text = text.replaceAll("\\n{3,}", "\n\n");

        return text;
    }

    /**
     * Corrige les erreurs OCR dans les montants
     * Exemples: "1O5.00" → "105.00", "l23.45" → "123.45"
     */
    private String correctAmounts(String text) {
        // Pattern pour détecter les montants (avec erreurs potentielles)
        Pattern amountPattern = Pattern.compile(
                "(\\d+[OolISsZB.,\\s]*\\d*[OolISsZB]*[.,]\\d{2})",
                Pattern.MULTILINE);

        Matcher matcher = amountPattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String amount = matcher.group(1);
            String corrected = correctAmountString(amount);
            matcher.appendReplacement(result, Matcher.quoteReplacement(corrected));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Corrige une chaîne de montant spécifique
     */
    private String correctAmountString(String amount) {
        String corrected = amount;

        // Remplacer O/o par 0
        corrected = corrected.replaceAll("[Oo]", "0");

        // Remplacer l/I par 1
        corrected = corrected.replaceAll("[lI]", "1");

        // Remplacer S/s par 5
        corrected = corrected.replaceAll("[Ss]", "5");

        // Remplacer Z par 2
        corrected = corrected.replaceAll("Z", "2");

        // Remplacer B par 8
        corrected = corrected.replaceAll("B", "8");

        // Supprimer espaces dans les nombres
        corrected = corrected.replaceAll("\\s+", "");

        // Normaliser séparateur décimal (virgule → point pour calculs)
        // Mais garder virgule pour affichage français

        return corrected;
    }

    /**
     * Normalise les formats de date
     * Exemples: "O9/O2/2O26" → "09/02/2026"
     */
    private String normalizeDates(String text) {
        // Pattern pour dates avec erreurs OCR
        Pattern datePattern = Pattern.compile(
                "(\\d{1,2}[OolI]?[/\\-.]\\d{1,2}[OolI]?[/\\-.]\\d{2,4}[OolI]?)",
                Pattern.MULTILINE);

        Matcher matcher = datePattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String date = matcher.group(1);
            String corrected = correctDateString(date);
            matcher.appendReplacement(result, Matcher.quoteReplacement(corrected));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Corrige une chaîne de date spécifique
     */
    private String correctDateString(String date) {
        String corrected = date;

        // Remplacer O/o par 0
        corrected = corrected.replaceAll("[Oo]", "0");

        // Remplacer l/I par 1
        corrected = corrected.replaceAll("[lI]", "1");

        return corrected;
    }

    /**
     * Corrige les numéros légaux (ICE, IF, RC)
     * Exemples: "ICE: OO1234567890123" → "ICE: 001234567890123"
     */
    private String correctLegalNumbers(String text) {
        // ICE (15 chiffres)
        text = correctLegalNumber(text, "ICE", 15);

        // IF (7-10 chiffres)
        text = correctLegalNumber(text, "IF", 10);
        text = correctLegalNumber(text, "I\\.?F\\.?", 10);

        // RC
        text = correctLegalNumber(text, "RC", 10);
        text = correctLegalNumber(text, "R\\.?C\\.?", 10);

        return text;
    }

    /**
     * Corrige un numéro légal spécifique
     */
    private String correctLegalNumber(String text, String prefix, int maxLength) {
        Pattern pattern = Pattern.compile(
                prefix + "\\s*[:.]?\\s*([\\dOolISsZB\\s]{7," + (maxLength + 5) + "})",
                Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String number = matcher.group(1);
            String corrected = correctNumberString(number);

            // Limiter à la longueur max
            if (corrected.length() > maxLength) {
                corrected = corrected.substring(0, maxLength);
            }

            String replacement = prefix + ": " + corrected;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Corrige une chaîne de chiffres
     */
    private String correctNumberString(String number) {
        String corrected = number;

        // Remplacer toutes les erreurs OCR communes
        corrected = corrected.replaceAll("[Oo]", "0");
        corrected = corrected.replaceAll("[lI]", "1");
        corrected = corrected.replaceAll("[Ss]", "5");
        corrected = corrected.replaceAll("Z", "2");
        corrected = corrected.replaceAll("B", "8");

        // Supprimer espaces
        corrected = corrected.replaceAll("\\s+", "");

        // Garder seulement les chiffres
        corrected = corrected.replaceAll("[^0-9]", "");

        return corrected;
    }

    /**
     * Valide un montant
     */
    public boolean isValidAmount(String amount) {
        if (amount == null || amount.isBlank()) {
            return false;
        }

        try {
            // Normaliser
            String normalized = amount.replaceAll("[^0-9.,]", "");
            normalized = normalized.replace(",", ".");

            double value = Double.parseDouble(normalized);

            // Montant doit être positif et raisonnable
            return value > 0 && value < 1_000_000_000;

        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Valide une date
     */
    public boolean isValidDate(String date) {
        if (date == null || date.isBlank()) {
            return false;
        }

        // Pattern date DD/MM/YYYY ou DD-MM-YYYY ou DD.MM.YYYY
        Pattern datePattern = Pattern.compile(
                "^(0?[1-9]|[12][0-9]|3[01])[/\\-.](0?[1-9]|1[0-2])[/\\-.](19|20)\\d{2}$");

        return datePattern.matcher(date.trim()).matches();
    }

    /**
     * Valide un numéro ICE (15 chiffres)
     */
    public boolean isValidICE(String ice) {
        if (ice == null) {
            return false;
        }

        String cleaned = ice.replaceAll("[^0-9]", "");
        return cleaned.length() == 15 && cleaned.matches("\\d{15}");
    }

    /**
     * Valide un numéro IF (7-10 chiffres)
     */
    public boolean isValidIF(String ifNumber) {
        if (ifNumber == null) {
            return false;
        }

        String cleaned = ifNumber.replaceAll("[^0-9]", "");
        return cleaned.length() >= 7 && cleaned.length() <= 10 && cleaned.matches("\\d+");
    }
}
