package com.example.releve_bancaire.releve_bancaire.mapper;

import com.example.releve_bancaire.releve_bancaire.dto.BankTransactionDTO;
import com.example.releve_bancaire.releve_bancaire.entity.BankTransaction;
import org.springframework.stereotype.Component;

/**
 * Mapper pour BankTransaction
 */
@Component
public class BankTransactionMapper {

    /**
     * Convertit une entité en DTO
     */
    public BankTransactionDTO toDTO(BankTransaction entity) {
        if (entity == null) {
            return null;
        }

        return BankTransactionDTO.builder()
                .id(entity.getId())
                .statementId(entity.getStatement() != null ? entity.getStatement().getId() : null)
                .dateOperation(entity.getDateOperation())
                .dateValeur(entity.getDateValeur())
                .libelle(entity.getLibelle())
                .rib(entity.getRib())
                .reference(entity.getReference())
                .code(entity.getCode())
                .debit(entity.getDebit())
                .credit(entity.getCredit())
                .sens(entity.getSens())
                .compte(entity.getCompte())
                .isLinked(entity.getIsLinked())
                .categorie(entity.getCategorie())
                .role(entity.getRole())
                .extractionConfidence(entity.getExtractionConfidence())
                .isValid(entity.getIsValid())
                .needsReview(entity.getNeedsReview())
                .extractionErrors(entity.getExtractionErrors())
                .reviewNotes(entity.getReviewNotes())
                .lineNumber(entity.getLineNumber())
                .transactionIndex(entity.getTransactionIndex())
                .build();
    }
}
