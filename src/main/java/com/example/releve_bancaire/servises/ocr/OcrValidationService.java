package com.example.releve_bancaire.servises.ocr;

import com.example.releve_bancaire.dto.ocr.OcrResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@Slf4j
public class OcrValidationService {

    // Seuils qualité
    private static final int HIGH_QUALITY_MIN_LENGTH = 200;
    private static final double HIGH_QUALITY_MIN_CONFIDENCE = 75.0;
    private static final double HIGH_QUALITY_MIN_CONFIDENCE_RELAXED = 60.0;
    private static final double HIGH_QUALITY_MIN_CONFIDENCE_STRONGLY_RELAXED = 55.0;
    private static final int ACCEPTABLE_MIN_LENGTH = 100;
    private static final double ACCEPTABLE_MIN_CONFIDENCE = 60.0;

    // Patterns détection champs
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d+[,.]\\d{2}");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}");
    private static final Pattern INVOICE_NUMBER_PATTERN = Pattern.compile("(?i)(facture|invoice).*\\d+");

    /**
     * Vérifie si résultat est haute qualité
     */
    public boolean isHighQuality(OcrResult result) {
        if (result == null || !result.isSuccess()) {
            return false;
        }

        String text = result.getText();
        double confidence = result.getConfidence();

        // Critère 1: Longueur
        if (text.length() < HIGH_QUALITY_MIN_LENGTH) {
            log.debug("Échec qualité: Texte trop court ({} < {})",
                    text.length(), HIGH_QUALITY_MIN_LENGTH);
            return false;
        }

        // Critère 2: Présence champs critiques
        int criticalFieldsFound = 0;
        if (AMOUNT_PATTERN.matcher(text).find())
            criticalFieldsFound++;
        if (DATE_PATTERN.matcher(text).find())
            criticalFieldsFound++;
        if (INVOICE_NUMBER_PATTERN.matcher(text).find())
            criticalFieldsFound++;

        if (criticalFieldsFound < 2) {
            log.debug("Échec qualité: Champs critiques insuffisants ({}/3)", criticalFieldsFound);
            return false;
        }

        // Critère 3: Confiance (adaptatif selon champs critiques)
        double minConfidence = HIGH_QUALITY_MIN_CONFIDENCE;
        if (criticalFieldsFound >= 3) {
            minConfidence = HIGH_QUALITY_MIN_CONFIDENCE_STRONGLY_RELAXED;
        } else if (criticalFieldsFound == 2) {
            minConfidence = HIGH_QUALITY_MIN_CONFIDENCE_RELAXED;
        }

        if (confidence < minConfidence) {
            log.debug("Échec qualité: Confiance faible ({}% < {}%)",
                    confidence, minConfidence);
            return false;
        }

        log.info("Haute qualité: {}chars, {}% confiance, {}/3 champs (seuil {}%)",
                text.length(), confidence, criticalFieldsFound, minConfidence);
        return true;
    }

    /**
     * Qualité élevée pour le header: critères plus souples
     */
    public boolean isHighQualityHeader(OcrResult result) {
        if (result == null || !result.isSuccess()) {
            return false;
        }

        String text = result.getText();
        double confidence = result.getConfidence();

        if (text.length() < 120) {
            log.debug("Échec qualité header: Texte trop court ({} < {})",
                    text.length(), 120);
            return false;
        }

        if (confidence < 60.0) {
            log.debug("Échec qualité header: Confiance faible ({}% < {}%)",
                    confidence, 60.0);
            return false;
        }

        int fieldsFound = 0;
        if (DATE_PATTERN.matcher(text).find())
            fieldsFound++;
        if (INVOICE_NUMBER_PATTERN.matcher(text).find())
            fieldsFound++;
        if (AMOUNT_PATTERN.matcher(text).find())
            fieldsFound++;

        if (fieldsFound >= 1) {
            log.info("Haute qualité header: {}chars, {}% confiance, {}/3 champs",
                    text.length(), confidence, fieldsFound);
            return true;
        }

        boolean longAndClear = text.length() >= 250 && confidence >= 62.0;
        if (longAndClear) {
            log.info("Haute qualité header (relaxé): {}chars, {}% confiance",
                    text.length(), confidence);
        }
        return longAndClear;
    }

    public boolean isAcceptable(OcrResult result) {
        if (result == null || !result.isSuccess()) {
            return false;
        }

        String text = result.getText();
        double confidence = result.getConfidence();

        boolean acceptable = text.length() >= ACCEPTABLE_MIN_LENGTH
                && confidence >= ACCEPTABLE_MIN_CONFIDENCE;

        if (acceptable) {
            log.info("Qualité acceptable: {}chars, {}% confiance",
                    text.length(), confidence);
        }

        return acceptable;
    }

    /**
     * Calcule score qualité détaillé
     */
    public QualityScore calculateDetailedScore(OcrResult result) {
        if (result == null || !result.isSuccess()) {
            return QualityScore.failed();
        }

        String text = result.getText();

        // Analyse détaillée
        int length = text.length();
        long alphanumCount = text.chars().filter(Character::isLetterOrDigit).count();
        double alphanumRatio = length > 0 ? (double) alphanumCount / length : 0;

        boolean hasAmount = AMOUNT_PATTERN.matcher(text).find();
        boolean hasDate = DATE_PATTERN.matcher(text).find();
        boolean hasInvoiceNumber = INVOICE_NUMBER_PATTERN.matcher(text).find();

        return QualityScore.builder()
                .textLength(length)
                .confidence(result.getConfidence())
                .alphanumRatio(alphanumRatio)
                .hasAmountField(hasAmount)
                .hasDateField(hasDate)
                .hasInvoiceNumber(hasInvoiceNumber)
                .overallScore(calculateOverallScore(length, result.getConfidence(), alphanumRatio,
                        hasAmount, hasDate, hasInvoiceNumber))
                .build();
    }

    private double calculateOverallScore(int length, double confidence, double alphanumRatio,
            boolean hasAmount, boolean hasDate, boolean hasInvoice) {
        double score = 0.0;

        // Longueur (max 30 points)
        score += Math.min(length / 10.0, 30);

        // Confiance (max 40 points)
        score += confidence * 0.4;

        // Ratio alphanum (max 20 points)
        score += alphanumRatio * 20;

        // Champs critiques (max 10 points)
        int fieldsCount = (hasAmount ? 1 : 0) + (hasDate ? 1 : 0) + (hasInvoice ? 1 : 0);
        score += fieldsCount * 3.33;

        return Math.min(score, 100.0);
    }

    /**
     * DTO Score qualité
     */
    @lombok.Data
    @lombok.Builder
    public static class QualityScore {
        private int textLength;
        private double confidence;
        private double alphanumRatio;
        private boolean hasAmountField;
        private boolean hasDateField;
        private boolean hasInvoiceNumber;
        private double overallScore;

        public static QualityScore failed() {
            return QualityScore.builder()
                    .textLength(0)
                    .confidence(0.0)
                    .alphanumRatio(0.0)
                    .hasAmountField(false)
                    .hasDateField(false)
                    .hasInvoiceNumber(false)
                    .overallScore(0.0)
                    .build();
        }
    }

}
