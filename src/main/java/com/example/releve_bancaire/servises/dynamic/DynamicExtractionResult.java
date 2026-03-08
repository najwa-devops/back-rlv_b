package com.example.releve_bancaire.servises.dynamic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * Résultat d'une extraction dynamique
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamicExtractionResult {

    private Long templateId;
    private String templateName;

    private Map<String, ExtractedField> extractedFields = new LinkedHashMap<>();
    private List<String> missingFields = new ArrayList<>();
    private List<String> lowConfidenceFields = new ArrayList<>();

    private Double overallConfidence;
    private Boolean complete;
    private Long extractionDurationMs;

    // ===================== MÉTHODES UTILISÉES PAR TES DTO/CONTROLLER
    // =====================

    public int getExtractedCount() {
        return extractedFields != null ? extractedFields.size() : 0;
    }

    public int getMissingCount() {
        return missingFields != null ? missingFields.size() : 0;
    }

    public boolean isComplete() {
        return Boolean.TRUE.equals(complete);
    }

    public Map<String, Object> toSimpleMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (extractedFields == null)
            return map;

        extractedFields.forEach((k, v) -> map.put(k, v.getNormalizedValue()));
        return map;
    }

    public Map<String, Object> toDetailedMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (extractedFields == null)
            return map;

        extractedFields.forEach((k, v) -> {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("value", v.getValue());
            field.put("normalizedValue", v.getNormalizedValue());
            field.put("confidence", v.getConfidence());
            field.put("detectionMethod", v.getDetectionMethod());
            field.put("validated", v.getValidated());
            field.put("validationError", v.getValidationError());
            map.put(k, field);
        });
        return map;
    }

    // ===================== SOUS-CLASSE =====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExtractedField {
        private String value;
        private Object normalizedValue;
        private Double confidence;
        private String detectionMethod;
        private Boolean validated;
        private String validationError;
    }
}
