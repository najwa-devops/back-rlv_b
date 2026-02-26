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
public class AttijariwafaTransactionParser extends AbstractTransactionParser {

    private static final Pattern START_PATTERN = Pattern.compile(
            "^\\s*(?:(\\d{6})|(?:\\d\\s+)?([0-9A-Z]{4,6}))\\s+" + DATE_ANY_PATTERN.pattern(),
            Pattern.CASE_INSENSITIVE);

    public AttijariwafaTransactionParser(OcrCleaningService cleaningService, TransactionClassifier classifier) {
        super(cleaningService, classifier);
    }

    @Override
    protected boolean isTransactionStart(String line) {
        return START_PATTERN.matcher(line).find();
    }

    @Override
    protected boolean useAwbNumericCodeStart() {
        return true;
    }

    @Override
    protected TransactionFields extractFields(List<String> block, Integer statementYear) {
        List<LocalDate> dates = extractDates(block, statementYear);
        if (dates.isEmpty()) {
            return null;
        }

        TransactionFields fields = new TransactionFields();
        fields.dateOperation = dates.get(0);
        fields.dateValeur = dates.size() >= 2 ? dates.get(dates.size() - 1) : fields.dateOperation;

        Matcher matcher = START_PATTERN.matcher(block.get(0));
        if (matcher.find()) {
            String numericCode = matcher.group(1);
            String alphaNumCode = matcher.group(2);
            String code = numericCode != null ? numericCode : alphaNumCode;
            if (code != null) {
                fields.code = code.toUpperCase();
            }
        }

        return fields;
    }

    private List<LocalDate> extractDates(List<String> block, Integer statementYear) {
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
