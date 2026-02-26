package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.banking_services.banking_ocr.OcrCleaningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class StatementTotalsExtractor {

    private final OcrCleaningService cleaningService;

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d+(?:[\\s\\.]\\d{3})*[,\\.]\\d{2}");

    public Totals extractTotals(String text) {
        if (text == null || text.isBlank()) {
            return new Totals(null, null);
        }

        BigDecimal totalDebit = null;
        BigDecimal totalCredit = null;

        String[] lines = text.split("\n");
        for (String line : lines) {
            String upper = line.toUpperCase();
            if (!upper.contains("TOTAL")) {
                continue;
            }
            if (upper.contains("SOLDE") || upper.contains("CAPITAL") || upper.contains("S.A")) {
                continue;
            }

            List<BigDecimal> amounts = extractAmounts(line);
            if (amounts.isEmpty()) {
                continue;
            }

            if (upper.contains("DEBIT") && upper.contains("CREDIT") && amounts.size() >= 2) {
                totalDebit = amounts.get(amounts.size() - 2);
                totalCredit = amounts.get(amounts.size() - 1);
                continue;
            }

            if (upper.contains("TOTAL MOUVEMENTS") || upper.contains("TOTAL DES MOUVEMENTS")) {
                if (amounts.size() >= 2) {
                    totalDebit = amounts.get(amounts.size() - 2);
                    totalCredit = amounts.get(amounts.size() - 1);
                }
                continue;
            }

            if (upper.contains("DEBIT") && totalDebit == null) {
                totalDebit = amounts.get(amounts.size() - 1);
                continue;
            }

            if (upper.contains("CREDIT") && totalCredit == null) {
                totalCredit = amounts.get(amounts.size() - 1);
            }
        }

        return new Totals(totalDebit, totalCredit);
    }

    private List<BigDecimal> extractAmounts(String line) {
        List<BigDecimal> amounts = new ArrayList<>();
        Matcher matcher = AMOUNT_PATTERN.matcher(line);
        while (matcher.find()) {
            String raw = matcher.group();
            try {
                BigDecimal value = new BigDecimal(cleaningService.normalizeAmount(raw));
                if (value.compareTo(BigDecimal.ZERO) < 0) {
                    value = value.abs();
                }
                amounts.add(value);
            } catch (Exception ignored) {
            }
        }
        return amounts;
    }

    public static class Totals {
        public final BigDecimal totalDebitPdf;
        public final BigDecimal totalCreditPdf;

        public Totals(BigDecimal totalDebitPdf, BigDecimal totalCreditPdf) {
            this.totalDebitPdf = totalDebitPdf;
            this.totalCreditPdf = totalCreditPdf;
        }
    }
}
