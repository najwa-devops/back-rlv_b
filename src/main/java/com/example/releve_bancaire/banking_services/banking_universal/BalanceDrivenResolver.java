package com.example.releve_bancaire.banking_services.banking_universal;

import com.example.releve_bancaire.banking_entity.BankTransaction;

import java.util.List;

public interface BalanceDrivenResolver {
    void resolve(List<BankTransaction> transactions);
}

