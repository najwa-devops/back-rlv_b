package com.example.releve_bancaire.banking_services.banking_universal;

import com.example.releve_bancaire.banking_services.BankType;

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

