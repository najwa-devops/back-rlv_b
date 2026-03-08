package com.example.releve_bancaire.dto.dynamic;

import com.example.releve_bancaire.entity.dynamic.FieldLearningData;
import com.example.releve_bancaire.entity.dynamic.LearningStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * DTO pour les réponses API concernant FieldLearningData
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldLearningDto {

    private Long id;
    private Long invoiceId;

    // Fournisseur
    private String supplierIce;
    private String supplierIf;
    private String supplierName;

    // Champ
    private String fieldName;
    private String fieldValue;
    private String detectedPattern;

    // Positions
    private Map<String, Double> patternPosition;
    private Map<String, Double> valuePosition;
    private String documentZone;

    // Métriques
    private Double patternValueDistance;
    private Double relativeAngle;
    private Double confidenceScore;

    // Apprentissage
    private LearningStatus status;
    private Long appliedToTemplateId;
    private Integer occurrenceCount;
    private String patternHash;

    // Contexte
    private String ocrContext;
    private String detectionMethod;

    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String rejectionReason;

    /**
     * Convertit une entité en DTO
     */
    public static FieldLearningDto fromEntity(FieldLearningData entity) {
        if (entity == null)
            return null;

        return FieldLearningDto.builder()
                .id(entity.getId())
                .invoiceId(entity.getInvoice() != null ? entity.getInvoice().getId() : null)
                .supplierIce(entity.getSupplierIce())
                .supplierIf(entity.getSupplierIf())
                .supplierName(entity.getSupplierName())
                .fieldName(entity.getFieldName())
                .fieldValue(entity.getFieldValue())
                .detectedPattern(entity.getDetectedPattern())
                .patternPosition(entity.getPatternPosition())
                .valuePosition(entity.getValuePosition())
                .documentZone(entity.getDocumentZone())
                .patternValueDistance(entity.getPatternValueDistance())
                .relativeAngle(entity.getRelativeAngle())
                .confidenceScore(entity.getConfidenceScore())
                .status(entity.getStatus())
                .appliedToTemplateId(entity.getAppliedToTemplateId())
                .occurrenceCount(entity.getOccurrenceCount())
                .patternHash(entity.getPatternHash())
                .ocrContext(entity.getOcrContext())
                .detectionMethod(entity.getDetectionMethod())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .createdBy(entity.getCreatedBy())
                .approvedBy(entity.getApprovedBy())
                .approvedAt(entity.getApprovedAt())
                .rejectionReason(entity.getRejectionReason())
                .build();
    }
}
