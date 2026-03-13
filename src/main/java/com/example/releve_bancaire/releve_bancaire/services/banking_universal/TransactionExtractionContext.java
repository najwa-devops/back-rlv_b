package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import com.example.releve_bancaire.releve_bancaire.services.BankType;

public record TransactionExtractionContext(
        BankType bankType,
        Integer statementMonth,
        Integer statementYear,
        boolean twoColumnAmountLayout) {

    /** Constructeur de compatibilité : layout par défaut = une colonne (comportement sûr). */
    public TransactionExtractionContext(BankType bankType, Integer statementMonth, Integer statementYear) {
        this(bankType, statementMonth, statementYear, false);
    }
}

