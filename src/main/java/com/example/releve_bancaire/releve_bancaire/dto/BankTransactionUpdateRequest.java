package com.example.releve_bancaire.releve_bancaire.dto;
import lombok.Data;

/**
 * DTO pour la modification d'une transaction
 */
@Data
public class BankTransactionUpdateRequest {
    private String compte;
    private Boolean isLinked;
    private String categorie;
    private String libelle;
}
