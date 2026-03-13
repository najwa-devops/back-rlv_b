package com.example.releve_bancaire.releve_bancaire.services.banking_parser;

import com.example.releve_bancaire.releve_bancaire.entity.BankTransaction;

import java.util.List;

public interface TransactionParser {
    List<BankTransaction> parse(String text, Integer statementYear);
}
