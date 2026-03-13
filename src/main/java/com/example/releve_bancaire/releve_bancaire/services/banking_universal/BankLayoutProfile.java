package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import java.util.Set;

public interface BankLayoutProfile {
    Set<String> creditHints();

    Set<String> debitHints();

    Set<String> ignoredLineHints();
}

