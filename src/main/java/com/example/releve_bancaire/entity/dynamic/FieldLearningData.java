package com.example.releve_bancaire.entity.dynamic;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldLearningData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ===================== RÉFÉRENCE FACTURE =====================

    /**
     * Facture source de cet apprentissage
     */
    @ManyToOne
    @JoinColumn(name = "invoice_id", nullable = false)
    private DynamicInvoice invoice;

    // ===================== IDENTIFICATION FOURNISSEUR =====================

    /**
     * ICE du fournisseur (pour retrouver le bon template)
     */
    @Column(name = "supplier_ice", length = 15)
    private String supplierIce;

    /**
     * IF du fournisseur
     */
    @Column(name = "supplier_if", length = 10)
    private String supplierIf;

    /**
     * Nom du fournisseur
     */
    @Column(name = "supplier_name", length = 200)
    private String supplierName;

    // ===================== DONNÉES DU CHAMP =====================

    /**
     * Nom du champ concerné
     * Exemple: "invoiceNumber", "supplier", "amountTTC"
     */
    @Column(name = "field_name", nullable = false, length = 50)
    private String fieldName;

    /**
     * Valeur extraite/corrigée par l'utilisateur
     * Exemple: "FAC-2026-001"
     */
    @Column(name = "field_value", columnDefinition = "TEXT")
    private String fieldValue;

    /**
     * Pattern/Label détecté par l'utilisateur
     * Exemple: "N° Facture", "Facture N°", "Invoice Number"
     */
    @Column(name = "detected_pattern", length = 200)
    private String detectedPattern;

    /**
     * Position du pattern dans le document (JSON)
     * Structure: { "x": 10.5, "y": 15.2, "width": 20.0, "height": 3.5 }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pattern_position", columnDefinition = "json")
    private Map<String, Double> patternPosition;

    /**
     * Position de la valeur dans le document (JSON)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_position", columnDefinition = "json")
    private Map<String, Double> valuePosition;

    /**
     * Zone du document (HEADER, BODY, FOOTER)
     */
    @Column(name = "document_zone", length = 20)
    private String documentZone;

    // ===================== MÉTRIQUES =====================

    /**
     * Distance entre le pattern et la valeur (en pixels)
     * Utile pour calculer où chercher la valeur par rapport au pattern
     */
    @Column(name = "pattern_value_distance")
    private Double patternValueDistance;

    /**
     * Angle relatif (en degrés) entre pattern et valeur
     * Exemple: 0° = à droite, 90° = en bas
     */
    @Column(name = "relative_angle")
    private Double relativeAngle;

    /**
     * Confiance de l'utilisateur (0.0 à 1.0)
     * Plus haute si l'utilisateur n'a pas hésité
     */
    @Column(name = "confidence_score")
    private Double confidenceScore;

    // ===================== APPRENTISSAGE =====================

    /**
     * Statut du pattern
     * PENDING: En attente de validation
     * APPROVED: Validé, intégré dans le template
     * REJECTED: Rejeté (pattern incorrect)
     * AUTO_APPROVED: Auto-approuvé (haute confiance)
     */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private LearningStatus status = LearningStatus.PENDING;

    /**
     * ID du template dans lequel ce pattern a été intégré
     */
    @Column(name = "applied_to_template_id")
    private Long appliedToTemplateId;

    /**
     * Nombre de fois où ce pattern a été détecté
     * (pour détecter les patterns récurrents)
     */
    @Column(name = "occurrence_count")
    private Integer occurrenceCount = 1;

    /**
     * Hash du pattern (pour détecter les doublons)
     * MD5(fieldName + pattern + supplierIce)
     */
    @Column(name = "pattern_hash", length = 64)
    private String patternHash;

    // ===================== CONTEXTE OCR =====================

    /**
     * Texte OCR brut autour du pattern (50 caractères avant/après)
     * Pour analyse de contexte
     */
    @Column(name = "ocr_context", columnDefinition = "TEXT")
    private String ocrContext;

    /**
     * Méthode de détection utilisée
     * USER_SELECTION: Sélection manuelle
     * AUTO_CORRECTION: Correction automatique
     * ML_SUGGESTION: Suggestion par ML
     */
    @Column(name = "detection_method", length = 30)
    private String detectionMethod = "USER_SELECTION";

    // ===================== AUDIT =====================

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    // ===================== LIFECYCLE =====================

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = LearningStatus.PENDING;
        }
        if (occurrenceCount == null) {
            occurrenceCount = 1;
        }
        if (confidenceScore == null) {
            confidenceScore = 0.5;
        }
        // Générer le hash
        if (patternHash == null) {
            generatePatternHash();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ===================== BUSINESS METHODS =====================

    /**
     * Génère un hash unique pour détecter les doublons
     */
    public void generatePatternHash() {
        String composite = fieldName + "|" +
                (detectedPattern != null ? detectedPattern : "") + "|" +
                (supplierIce != null ? supplierIce : "");
        this.patternHash = Integer.toHexString(composite.hashCode());
    }

    /**
     * Approuve ce pattern d'apprentissage
     */
    public void approve(String approvedBy) {
        this.status = LearningStatus.APPROVED;
        this.approvedBy = approvedBy;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * Rejette ce pattern
     */
    public void reject(String rejectedBy, String reason) {
        this.status = LearningStatus.REJECTED;
        this.approvedBy = rejectedBy;
        this.approvedAt = LocalDateTime.now();
        this.rejectionReason = reason;
    }

    /**
     * Auto-approuve (haute confiance)
     */
    public void autoApprove() {
        this.status = LearningStatus.AUTO_APPROVED;
        this.approvedBy = "SYSTEM";
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * Marque comme appliqué à un template
     */
    public void markAsApplied(Long templateId) {
        this.appliedToTemplateId = templateId;
    }

    /**
     * Incrémente le compteur d'occurrences
     */
    public void incrementOccurrence() {
        if (this.occurrenceCount == null) {
            this.occurrenceCount = 1;
        } else {
            this.occurrenceCount++;
        }
    }

    /**
     * Vérifie si le pattern est prêt pour l'intégration
     */
    public boolean isReadyForIntegration() {
        return status == LearningStatus.APPROVED && appliedToTemplateId == null;
    }

    /**
     * Vérifie si c'est un pattern haute confiance
     */
    public boolean isHighConfidence() {
        return confidenceScore != null && confidenceScore >= 0.85;
    }

    /**
     * Calcule la distance entre pattern et valeur
     */
    public void calculateDistance() {
        if (patternPosition != null && valuePosition != null) {
            Double px = patternPosition.get("x");
            Double py = patternPosition.get("y");
            Double vx = valuePosition.get("x");
            Double vy = valuePosition.get("y");

            if (px != null && py != null && vx != null && vy != null) {
                this.patternValueDistance = Math.sqrt(
                        Math.pow(vx - px, 2) + Math.pow(vy - py, 2));

                // Calculer l'angle
                this.relativeAngle = Math.toDegrees(Math.atan2(vy - py, vx - px));
            }
        }
    }
}
