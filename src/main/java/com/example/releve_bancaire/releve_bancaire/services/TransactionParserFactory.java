package com.example.releve_bancaire.releve_bancaire.services;

import com.example.releve_bancaire.releve_bancaire.services.banking_parser.AttijariwafaTransactionParser;
import com.example.releve_bancaire.releve_bancaire.services.banking_parser.BcpTransactionParser;
import com.example.releve_bancaire.releve_bancaire.services.banking_parser.DateOpDateValTransactionParser;
import com.example.releve_bancaire.releve_bancaire.services.banking_parser.LibelleDateValTransactionParser;
import com.example.releve_bancaire.releve_bancaire.services.banking_parser.StandardTransactionParser;
import com.example.releve_bancaire.releve_bancaire.services.banking_parser.TransactionParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionParserFactory {

    private final StandardTransactionParser standardParser;
    private final BcpTransactionParser bcpParser;
    private final AttijariwafaTransactionParser attijariwafaParser;
    private final DateOpDateValTransactionParser dateOpDateValParser;
    private final LibelleDateValTransactionParser libelleDateValParser;

    public TransactionParser getParser(BankType bankType) {
        if (bankType == null) {
            return standardParser;
        }
        return switch (bankType) {
            case BCP -> bcpParser;
            case CREDIT_DU_MAROC, BMCI, CIH, SOCIETE_GENERALE, BARID_BANK -> dateOpDateValParser;
            case ATTIJARIWAFA -> attijariwafaParser;
            case CREDIT_AGRICOLE, BMCE -> libelleDateValParser;
            default -> standardParser;
        };
    }
}
