package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import com.example.releve_bancaire.releve_bancaire.entity.BankTransaction;

import java.util.List;

public interface BalanceDrivenResolver {
    void resolve(List<BankTransaction> transactions);
}

