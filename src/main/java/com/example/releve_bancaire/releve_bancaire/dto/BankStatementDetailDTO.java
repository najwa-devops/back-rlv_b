package com.example.releve_bancaire.releve_bancaire.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * DTO détaillé pour un relevé bancaire
 * (inclut les transactions)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BankStatementDetailDTO extends BankStatementDTO {

    private String accountHolder;
    private String validationErrors;
    private String filePath;
    private String validatedBy;

    // OCR (optionnel, non inclus par défaut)
    private String rawOcrText;
    private String cleanedOcrText;

    // Transactions (preview ou complet)
    private List<BankTransactionDTO> transactions;
    private Integer transactionsPreviewCount; // Nombre de transactions en preview
}
