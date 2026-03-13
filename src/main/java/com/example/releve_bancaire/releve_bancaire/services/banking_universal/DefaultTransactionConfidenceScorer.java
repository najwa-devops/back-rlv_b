package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import com.example.releve_bancaire.releve_bancaire.entity.BankTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DefaultTransactionConfidenceScorer implements TransactionConfidenceScorer {

    @Override
    public int score(BankTransaction tx) {
        int score = 0;

        if (tx.getDateOperation() != null) {
            score += 25;
        } else {
            tx.getFlags().add("INVALID_DATE");
        }

        boolean amountOk = tx.getDebit() != null && tx.getDebit().compareTo(BigDecimal.ZERO) >= 0
                && tx.getCredit() != null && tx.getCredit().compareTo(BigDecimal.ZERO) >= 0
                && tx.getDebit().add(tx.getCredit()).compareTo(BigDecimal.ZERO) > 0;
        if (amountOk) {
            score += 30;
        } else {
            tx.getFlags().add("INVALID_AMOUNT");
        }

        if (tx.getBalance() != null) {
            if (tx.getFlags().contains("BALANCE_MISMATCH")) {
                score += 10;
            } else {
                score += 30;
            }
        } else {
            score += 15;
            tx.getFlags().add("MISSING_BALANCE");
        }

        if (tx.getLibelle() != null && !tx.getLibelle().isBlank()) {
            score += 15;
        } else {
            tx.getFlags().add("EMPTY_DESCRIPTION");
        }

        return Math.max(0, Math.min(score, 100));
    }
}

