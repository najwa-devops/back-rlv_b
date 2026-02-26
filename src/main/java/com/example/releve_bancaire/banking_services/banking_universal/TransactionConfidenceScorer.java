package com.example.releve_bancaire.banking_services.banking_universal;

import com.example.releve_bancaire.banking_entity.BankTransaction;

public interface TransactionConfidenceScorer {
    int score(BankTransaction transaction);
}

