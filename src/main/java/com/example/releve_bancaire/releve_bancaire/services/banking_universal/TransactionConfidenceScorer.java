package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import com.example.releve_bancaire.releve_bancaire.entity.BankTransaction;

public interface TransactionConfidenceScorer {
    int score(BankTransaction transaction);
}

