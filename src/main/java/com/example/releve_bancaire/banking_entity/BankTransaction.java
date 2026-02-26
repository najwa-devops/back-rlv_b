package com.example.releve_bancaire.banking_entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bank_transaction", indexes = {
        @Index(name = "idx_tx_statement_id", columnList = "statement_id"),
        @Index(name = "idx_tx_date_operation", columnList = "date_operation"),
        @Index(name = "idx_tx_is_valid", columnList = "is_valid"),
        @Index(name = "idx_tx_needs_review", columnList = "needs_review")
})
@Data
@NoArgsConstructor
public class BankTransaction {

    private static final String DEFAULT_COMPTE = "349700000";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==================== RELATION ====================

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id", nullable = false)
    private BankStatement statement;

    // ==================== DATES ====================

    @Column(name = "date_operation", nullable = false)
    private LocalDate dateOperation;

    @Column(name = "date_valeur")
    private LocalDate dateValeur;

    // ==================== TRANSACTION ====================

    @Column(nullable = false, length = 1000)
    private String libelle;

    @Column(length = 30)
    private String rib;

    @Column(length = 10)
    private String reference;

    @Column(length = 10)
    private String code;

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(length = 10, nullable = false)
    private String sens = "DEBIT"; // DEBIT / CREDIT

    // ==================== COMPTABILITÉ ====================

    @Column(length = 20, nullable = false)
    private String compte = DEFAULT_COMPTE;

    @Column(nullable = false)
    private Boolean isLinked = false;

    // ==================== CLASSIFICATION ====================

    @Column(length = 50)
    private String categorie;

    @Column(length = 20)
    private String role;

    // ==================== QUALITÉ ====================

    @Column
    private Double extractionConfidence;

    @Column(name = "is_valid", nullable = false)
    private Boolean isValid = true;

    @Column(name = "needs_review", nullable = false)
    private Boolean needsReview = false;

    @Column(columnDefinition = "TEXT")
    private String extractionErrors;

    // ==================== OCR (ligne) ====================

    @Column
    private Integer lineNumber;

    @Column(length = 500)
    private String rawOcrLinePath; // chemin fichier OCR ligne

    @Column(columnDefinition = "TEXT")
    private String rawOcrLine;

    @Column(columnDefinition = "TEXT")
    private String reviewNotes;

    // ==================== MÉTIER ====================

    @Column(name = "transaction_index")
    private Integer transactionIndex; // Numéro séquentiel dans le relevé

    @Transient
    private BigDecimal balance;

    @Transient
    private Integer confidenceScore;

    @Transient
    private List<String> flags = new ArrayList<>();

    public BigDecimal getAbsoluteAmount() {
        if (debit != null && debit.compareTo(BigDecimal.ZERO) > 0) {
            return debit;
        }
        return credit != null ? credit : BigDecimal.ZERO;
    }

    public boolean isModifiable() {
        return statement != null && statement.isModifiable();
    }

    public void validate() {
        if (dateOperation == null) {
            markAsInvalid("Date d'opération manquante");
            return;
        }
        if (libelle == null || libelle.isBlank()) {
            markAsInvalid("Libellé manquant");
            return;
        }
        if (getAbsoluteAmount().compareTo(BigDecimal.ZERO) == 0) {
            markAsInvalid("Montant nul");
            return;
        }
        if (!"DEBIT".equals(sens) && !"CREDIT".equals(sens)) {
            markAsInvalid("Sens invalide");
            return;
        }

        this.isValid = true;
        this.needsReview = false;
        this.extractionErrors = null;
    }

    private void markAsInvalid(String error) {
        this.isValid = false;
        this.needsReview = true;
        this.extractionErrors = error;
    }

    public void checkConfidence(double threshold) {
        if (extractionConfidence != null && extractionConfidence < threshold) {
            this.needsReview = true;
        }
    }

    public void applyAccountingRules() {
        if (libelle == null) {
            return;
        }
        if (this.compte == null || this.compte.isBlank()) {
            this.compte = DEFAULT_COMPTE;
        }
        if (DEFAULT_COMPTE.equals(this.compte.trim())) {
            this.isLinked = false;
        }
    }
}
