package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import com.example.releve_bancaire.releve_bancaire.services.BankType;

public interface BankLayoutProfileRegistry {
    BankLayoutProfile getProfile(BankType bankType);
}

