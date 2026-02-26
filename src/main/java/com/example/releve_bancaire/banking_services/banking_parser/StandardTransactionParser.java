package com.example.releve_bancaire.banking_services.banking_parser;

import com.example.releve_bancaire.banking_services.TransactionClassifier;
import com.example.releve_bancaire.banking_services.banking_ocr.OcrCleaningService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

@Component
public class StandardTransactionParser extends AbstractTransactionParser {

    public StandardTransactionParser(OcrCleaningService cleaningService, TransactionClassifier classifier) {
        super(cleaningService, classifier);
    }

    @Override
    protected boolean isTransactionStart(String line) {
        return DATE_START_PATTERN.matcher(line).find();
    }

    @Override
    protected TransactionFields extractFields(List<String> block, Integer statementYear) {
        List<LocalDate> dates = extractDates(block, statementYear);
        if (dates.isEmpty()) {
            return null;
        }

        TransactionFields fields = new TransactionFields();
        fields.dateOperation = dates.get(0);
        if (dates.size() >= 2) {
            fields.dateValeur = dates.get(1);
        } else {
            fields.dateValeur = fields.dateOperation;
        }
        return fields;
    }

    protected List<LocalDate> extractDates(List<String> block, Integer statementYear) {
        List<LocalDate> dates = new ArrayList<>();
        for (String line : block) {
            Matcher matcher = DATE_ANY_PATTERN.matcher(line);
            while (matcher.find()) {
                LocalDate date = parseDate(matcher.group(1), matcher.group(2), matcher.group(3), statementYear);
                if (date != null) {
                    dates.add(date);
                }
            }
        }
        return dates;
    }
}
