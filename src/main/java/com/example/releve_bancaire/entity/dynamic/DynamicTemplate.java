package com.example.releve_bancaire.entity.dynamic;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.example.releve_bancaire.entity.account_tier.Tier;
import com.example.releve_bancaire.entity.template.TemplateSignature;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Template dynamique pour l'extraction de factures.
 *
 * DIFFÉRENCE avec InvoiceTemplate :
 * - Ne dépend PAS des coordonnées (x, y, width, height)
 * - Basé sur des labels et regex pour une extraction robuste
 * - Réutilisable pour plusieurs mises en page du même fournisseur
 */
@Entity
@Table(name = "dynamic_template", indexes = {
        @Index(name = "idx_dynamic_template_signature", columnList = "signature_type, signature_value"),
        @Index(name = "idx_dynamic_template_supplier_type", columnList = "supplier_type"),
        @Index(name = "idx_dynamic_template_active", columnList = "active")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamicTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nom du template (ex: "Maroc Telecom - Standard")
    @Column(name = "template_name", nullable = false)
    private String templateName;

    // Type de fournisseur (ex: MAROC_TELECOM, ONE, LYDEC, GENERIC)

    @Column(name = "supplier_type", nullable = false)
    private String supplierType;

    // Signature unique du fournisseur (ICE, IF ou nom)

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "signatureType", column = @Column(name = "signature_type")),
            @AttributeOverride(name = "signatureValue", column = @Column(name = "signature_value"))
    })
    private TemplateSignature signature;

    // Définitions des champs à extraire (stocké en JSON)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_definitions", columnDefinition = "json")
    private List<DynamicFieldDefinitionJson> fieldDefinitions;

    // Données fixes du fournisseur (ICE, IF, RC, nom, adresse)
    // Ces données ne changent JAMAIS pour un fournisseur donné
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fixed_supplier_data", columnDefinition = "json")
    private FixedSupplierData fixedSupplierData;

    // Template actif ou non

    @Column(nullable = false)
    private Boolean active = true;

    // Version du template (v1, v2, v3...)

    @Column(nullable = false)
    private Integer version = 1;

    // Description du template

    @Column(length = 500)
    private String description;

    // === MÉTRIQUES ===

    @Column(name = "usage_count")
    private Integer usageCount = 0;

    @Column(name = "success_count")
    private Integer successCount = 0;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    // ===================== LIEN AVEC TIER =====================

    /**
     * Lien vers le Tier (fournisseur)
     * Optionnel: permet de lier un template à un fournisseur enregistré
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id")
    private Tier tier;

    /**
     * ID du Tier (pour éviter le lazy loading)
     */
    @Column(name = "tier_id", insertable = false, updatable = false)
    private Long tierId;

    // === CLASSES INTERNES ===

    // Définition d'un champ en format JSON (pour stockage JSON)

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DynamicFieldDefinitionJson {
        private String fieldName;
        private List<String> labels;
        private String regexPattern;
        private String fieldType; // TEXT, NUMBER, DATE, CURRENCY, IDENTIFIER
        private String detectionMethod; // LABEL_BASED, REGEX_BASED, HYBRID, CONTEXTUAL
        private Boolean required;
        private Double confidenceThreshold;
        private String defaultValue;
        private String searchZone; // HEADER, BODY, FOOTER, ALL
        private Integer extractionOrder;
        private String description;
        private List<String> patterns;

        // Helper pour conversion
        @JsonIgnore
        public FieldType getFieldTypeEnum() {
            return fieldType != null ? FieldType.valueOf(fieldType) : FieldType.TEXT;
        }

        @JsonIgnore
        public DetectionMethod getDetectionMethodEnum() {
            return detectionMethod != null ? DetectionMethod.valueOf(detectionMethod) : DetectionMethod.HYBRID;
        }
    }

    // Données fixes du fournisseur

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FixedSupplierData {
        private String ice; // ICE (15 chiffres)
        private String ifNumber; // IF (7-10 chiffres)
        private String rcNumber; // RC
        private String supplier; // Nom du fournisseur
        private String address; // Adresse
        private String phone; // Téléphone
        private String email; // Email
        private String city; // Ville
        private String postalCode; // Code postal
    }

    // === LIFECYCLE ===

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (version == null)
            version = 1;
        if (usageCount == null)
            usageCount = 0;
        if (successCount == null)
            successCount = 0;
        if (active == null)
            active = true;
        if (fieldDefinitions == null)
            fieldDefinitions = new ArrayList<>();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // === MÉTHODES UTILITAIRES ===

    public void incrementUsage() {
        this.usageCount++;
        this.lastUsedAt = LocalDateTime.now();
    }

    public void incrementSuccess() {
        this.successCount++;
    }

    public double getSuccessRate() {
        if (usageCount == null || usageCount == 0)
            return 0.0;
        return (double) successCount / usageCount * 100.0;
    }

    public boolean isReliable() {
        return usageCount != null && usageCount >= 5 && getSuccessRate() >= 80.0;
    }

    // Obtient la définition d'un champ par son nom

    public DynamicFieldDefinitionJson getFieldDefinition(String fieldName) {
        if (fieldDefinitions == null)
            return null;
        return fieldDefinitions.stream()
                .filter(f -> f.getFieldName().equals(fieldName))
                .findFirst()
                .orElse(null);
    }

    // Vérifie si un champ est défini dans ce template

    public boolean hasField(String fieldName) {
        return getFieldDefinition(fieldName) != null;
    }

    // Retourne les champs obligatoires

    public List<DynamicFieldDefinitionJson> getRequiredFields() {
        if (fieldDefinitions == null)
            return new ArrayList<>();
        return fieldDefinitions.stream()
                .filter(f -> Boolean.TRUE.equals(f.getRequired()))
                .toList();
    }

    // Retourne les noms des champs définis

    public List<String> getFieldNames() {
        if (fieldDefinitions == null)
            return new ArrayList<>();
        return fieldDefinitions.stream()
                .map(DynamicFieldDefinitionJson::getFieldName)
                .toList();
    }

    /**
     * Récupère le compte fournisseur depuis le Tier
     */

    /**
     * Récupère le compte de charge depuis le Tier
     */
    public String getChargeAccount() {
        return tier != null ? tier.getDefaultChargeAccount() : null;
    }

    /**
     * Récupère le compte TVA depuis le Tier
     */
    public String getTvaAccount() {
        return tier != null ? tier.getTvaAccount() : null;
    }
}
