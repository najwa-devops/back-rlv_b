package com.example.releve_bancaire.banking_services.banking_parser;

import com.example.releve_bancaire.banking_services.TransactionClassifier;
import com.example.releve_bancaire.banking_services.banking_ocr.OcrCleaningService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BcpTransactionParser extends AbstractTransactionParser {

    private static final String DATE_TOKEN =
            "(?:\\d{1,2})\\s*[\\/\\-.]\\s*(?:\\d{1,2})(?:\\s*[\\/\\-.]\\s*(?:\\d{2,4}))?";
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{1,2})\\s*[\\/\\-.]\\s*(\\d{1,2})(?:\\s*[\\/\\-.]\\s*(\\d{2,4}))?");
    private static final Pattern START_PATTERN = Pattern.compile(
            "^\\s*" + DATE_TOKEN + "\\s+" + DATE_TOKEN + "\\s+\\d{6}\\b");
    private static final Pattern REF_PATTERN = Pattern.compile(
            "^\\s*" + DATE_TOKEN + "\\s+" + DATE_TOKEN + "\\s+(\\d{6})\\b");

    public BcpTransactionParser(OcrCleaningService cleaningService, TransactionClassifier classifier) {
        super(cleaningService, classifier);
    }

    @Override
    protected boolean isTransactionStart(String line) {
        return START_PATTERN.matcher(line).find();
    }

    @Override
    protected TransactionFields extractFields(List<String> block, Integer statementYear) {
        List<LocalDate> dates = extractDates(block, statementYear);
        if (dates.size() < 2) {
            return null;
        }

        TransactionFields fields = new TransactionFields();
        fields.dateOperation = dates.get(0);
        fields.dateValeur = dates.get(1);

        Matcher matcher = REF_PATTERN.matcher(block.get(0));
        if (matcher.find()) {
            fields.reference = matcher.group(1);
        }

        return fields;
    }

    private List<LocalDate> extractDates(List<String> block, Integer statementYear) {
        List<LocalDate> dates = new ArrayList<>();
        for (String line : block) {
            Matcher matcher = DATE_PATTERN.matcher(line);
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
