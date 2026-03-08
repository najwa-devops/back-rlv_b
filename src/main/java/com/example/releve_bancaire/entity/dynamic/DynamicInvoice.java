package com.example.releve_bancaire.entity.dynamic;

import com.example.releve_bancaire.entity.account_tier.Tier;
import com.example.releve_bancaire.entity.auth.Dossier;
import com.example.releve_bancaire.entity.invoice.InvoiceStatus;
import com.example.releve_bancaire.entity.template.TemplateSignature;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Entité DynamicInvoice
 * VERSION AVEC MODE AUXILIAIRE - SANS supplierAccount
 */
@Entity
@Data
@Table(name = "dynamic_invoice")
@NoArgsConstructor
@AllArgsConstructor
public class DynamicInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String originalName;

    private String filePath;

    private Long fileSize;

    // ===================== OCR =====================

    @Column(columnDefinition = "TEXT")
    private String rawOcrText;

    // ===================== EXTRACTION (SOURCE DE VÉRITÉ) =====================

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> extractedData;

    // ===================== LEGACY / UI =====================

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> fieldsData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<String> missingFields;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<String> lowConfidenceFields;

    // ===================== AUTO-FILLED (depuis le template) =====================

    /**
     * Liste des champs qui ont été automatiquement remplis
     * depuis le dynamic_template (fixedSupplierData).
     * Utilisé par le frontend pour afficher un badge "Auto" sur ces champs.
     * Exemple: ["ice", "supplier", "ifNumber", "rcNumber"]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auto_filled_fields", columnDefinition = "json")
    private List<String> autoFilledFields;

    // ===================== TEMPLATE =====================

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "signatureType", column = @Column(name = "signature_type")),
            @AttributeOverride(name = "signatureValue", column = @Column(name = "signature_value"))
    })
    private TemplateSignature detectedSignature;

    private Long templateId;

    private String templateName;

    private Double overallConfidence;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id")
    private Tier tier;

    @Column(name = "tier_id", insertable = false, updatable = false)
    private Long tierId;

    @Column(name = "tier_name", length = 200)
    private String tierName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id")
    private Dossier dossier;

    @Column(name = "dossier_id", insertable = false, updatable = false)
    private Long dossierId;

    // ===================== STATUS =====================

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.PENDING;

    @Column(name = "client_validated")
    private Boolean clientValidated = false;

    @Column(name = "client_validated_at")
    private LocalDateTime clientValidatedAt;

    @Column(name = "client_validated_by")
    private String clientValidatedBy;

    private LocalDateTime validatedAt;

    private String validatedBy;

    @Column(name = "accounted")
    private Boolean accounted = false;

    @Column(name = "accounted_at")
    private LocalDateTime accountedAt;

    @Column(name = "accounted_by")
    private String accountedBy;

    private Long learningSnapshotId;

    // ===================== TYPE FACTURE =====================

    @Column(name = "is_avoir")
    private Boolean isAvoir = false;

    // ===================== AUDIT =====================

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ===================== LIFECYCLE =====================

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();

        if (extractedData == null)
            extractedData = new HashMap<>();
        if (fieldsData == null)
            fieldsData = new HashMap<>();
        if (missingFields == null)
            missingFields = new ArrayList<>();
        if (lowConfidenceFields == null)
            lowConfidenceFields = new ArrayList<>();
        if (autoFilledFields == null)
            autoFilledFields = new ArrayList<>();
        if (status == null)
            status = InvoiceStatus.PENDING;
        if (clientValidated == null)
            clientValidated = false;
        if (accounted == null)
            accounted = false;
        if (isAvoir == null)
            isAvoir = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ===================== HELPERS (UI SAFE) =====================

    public Object getField(String fieldName) {
        return fieldsData != null ? fieldsData.get(fieldName) : null;
    }

    public void setField(String fieldName, Object value) {
        if (fieldsData == null)
            fieldsData = new HashMap<>();
        fieldsData.put(fieldName, value);
    }

    public String getFieldAsString(String fieldName) {
        Object value = getField(fieldName);
        return value != null ? value.toString() : null;
    }

    public Double getFieldAsDouble(String fieldName) {
        Object value = getField(fieldName);
        if (value == null)
            return null;
        if (value instanceof Number n)
            return n.doubleValue();
        try {
            return Double.parseDouble(value.toString().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ===================== DOMAIN HELPERS =====================

    public String getInvoiceNumber() {
        return getFieldAsString("invoiceNumber");
    }

    public String getSupplier() {
        return getFieldAsString("supplier");
    }

    public String getIce() {
        return getFieldAsString("ice");
    }

    public String getIfNumber() {
        return getFieldAsString("ifNumber");
    }

    public String getRcNumber() {
        return getFieldAsString("rcNumber");
    }

    public String getInvoiceDate() {
        return getFieldAsString("invoiceDate");
    }

    public Double getAmountHT() {
        return getFieldAsDouble("amountHT");
    }

    public Double getTva() {
        return getFieldAsDouble("tva");
    }

    public Double getAmountTTC() {
        return getFieldAsDouble("amountTTC");
    }

    public boolean isModifiable() {
        return status != InvoiceStatus.VALIDATED;
    }

    public boolean isAccounted() {
        return Boolean.TRUE.equals(accounted);
    }

    public boolean canBeValidated() {
        return status == InvoiceStatus.READY_TO_VALIDATE;
    }

    public boolean canLearnTemplate() {
        return detectedSignature != null
                && detectedSignature.isValid()
                && status == InvoiceStatus.READY_TO_VALIDATE
                && learningSnapshotId == null;
    }

    public void validate(String userId) {
        if (!canBeValidated()) {
            throw new IllegalStateException(
                    "Seules les factures READY_TO_VALIDATE peuvent être validées. Statut actuel: " + status);
        }
        this.status = InvoiceStatus.VALIDATED;
        this.validatedAt = LocalDateTime.now();
        this.validatedBy = userId;
    }

    public void markAsTreated() {
        if (this.status == InvoiceStatus.PROCESSING || this.status == InvoiceStatus.PENDING) {
            this.status = InvoiceStatus.TREATED;
        }
    }

    public void markAsError() {
        this.status = InvoiceStatus.ERROR;
    }

    public void startProcessing() {
        if (this.status == InvoiceStatus.PENDING || this.status == InvoiceStatus.ERROR) {
            this.status = InvoiceStatus.PROCESSING;
        }
    }

    // ===================== TTC LOGIC =====================

    public Double calculateTTC() {
        Double ht = getAmountHT();
        Double tva = getTva();
        if (ht != null && tva != null)
            return ht + tva;
        return null;
    }

    public boolean isTTCConsistent() {
        Double calculated = calculateTTC();
        Double extracted = getAmountTTC();
        if (calculated == null || extracted == null)
            return true;
        return Math.abs(calculated - extracted) <= 0.01;
    }

    public Double getTTCDifference() {
        Double calculated = calculateTTC();
        Double extracted = getAmountTTC();
        if (calculated == null || extracted == null)
            return null;
        return extracted - calculated;
    }

    // ===================== TIER HELPERS (MODE AUXILIAIRE) =====================

    public boolean hasTier() {
        return tier != null || tierId != null;
    }

    /**
     * Retourne le numéro de tier (TOUJOURS présent)
     * Remplace l'ancien supplierAccount
     */
    public String getTierNumber() {
        return tier != null ? tier.getTierNumber() : null;
    }

    /**
     * Retourne le compte collectif (si mode auxiliaire)
     */
    public String getCollectifAccount() {
        return tier != null ? tier.getCollectifAccount() : null;
    }

    /**
     * Retourne le compte pour affichage
     * Mode auxiliaire: "441100000 / 000000123"
     * Mode normal: "000000123"
     */
    public String getDisplayAccount() {
        return tier != null ? tier.getDisplayAccount() : null;
    }

    /**
     * Retourne le mode auxiliaire du tier
     */
    public Boolean isAuxiliaireMode() {
        return tier != null ? tier.getAuxiliaireMode() : null;
    }

    public String getChargeAccount() {
        return tier != null ? tier.getDefaultChargeAccount() : null;
    }

    public String getTvaAccount() {
        return tier != null ? tier.getTvaAccount() : null;
    }

    public Double getDefaultTvaRate() {
        return tier != null ? tier.getDefaultTvaRate() : null;
    }

    public boolean hasTierAccountingConfig() {
        return tier != null && tier.hasAccountingConfiguration();
    }

    @Override
    public String toString() {
        return "DynamicInvoice{" +
                "id=" + id +
                ", invoiceNumber='" + getInvoiceNumber() + '\'' +
                ", tierId=" + tierId +
                ", tierName='" + tierName + '\'' +
                ", status=" + status +
                ", amountTTC=" + getAmountTTC() +
                '}';
    }
}
