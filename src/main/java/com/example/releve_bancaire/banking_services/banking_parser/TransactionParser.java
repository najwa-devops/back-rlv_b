package com.example.releve_bancaire.banking_services.banking_parser;

import com.example.releve_bancaire.banking_entity.BankTransaction;

import java.util.List;

public interface TransactionParser {
    List<BankTransaction> parse(String text, Integer statementYear);
}
