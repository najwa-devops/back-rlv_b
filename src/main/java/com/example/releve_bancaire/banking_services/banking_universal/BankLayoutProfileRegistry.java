package com.example.releve_bancaire.banking_services.banking_universal;

import com.example.releve_bancaire.banking_services.BankType;

public interface BankLayoutProfileRegistry {
    BankLayoutProfile getProfile(BankType bankType);
}

