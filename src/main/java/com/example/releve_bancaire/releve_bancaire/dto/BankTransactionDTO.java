package com.example.releve_bancaire.releve_bancaire.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO pour les transactions bancaires
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BankTransactionDTO {

    private Long id;
    private Long statementId;

    // Dates
    private LocalDate dateOperation;
    private LocalDate dateValeur;

    // Transaction
    private String libelle;
    private String rib;
    private String reference;
    private String code;
    private BigDecimal debit;
    private BigDecimal credit;
    private String sens;

    // Comptabilité
    private String compte;
    private Boolean isLinked;

    // Classification
    private String categorie;
    private String role;

    // Qualité
    private Double extractionConfidence;
    private Boolean isValid;
    private Boolean needsReview;
    private String extractionErrors;
    private String reviewNotes;

    // OCR
    private Integer lineNumber;
    private Integer transactionIndex;
}
