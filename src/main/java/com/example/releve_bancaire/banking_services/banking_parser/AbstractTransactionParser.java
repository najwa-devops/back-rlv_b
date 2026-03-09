package com.example.releve_bancaire.banking_services.banking_parser;

import com.example.releve_bancaire.banking_entity.BankTransaction;
import com.example.releve_bancaire.banking_services.TransactionClassifier;
import com.example.releve_bancaire.banking_services.banking_ocr.OcrCleaningService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractTransactionParser implements TransactionParser {

    protected static final Pattern DATE_ANY_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})(?:\\s*[\\/\\-.\\s]\\s*(\\d{2,4}))?(?!\\d)");
    protected static final Pattern DATE_START_PATTERN =
            Pattern.compile("^\\s*(?:0?[1-9]|[12]\\d|3[01])\\s*[\\/\\-.\\s]\\s*(?:0?[1-9]|1[0-2])\\b");
    protected static final Pattern AWB_NUMERIC_CODE_START_PATTERN =
            Pattern.compile("^\\s*(?:\\d\\s*){6}\\b");
    protected static final Pattern AMOUNT_PATTERN =
            Pattern.compile("(?<!\\d)(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,\\.]\\d{2}(?!\\d)");
    protected static final Pattern EXPLODED_AMOUNT_PATTERN =
            Pattern.compile("(?<!\\d)((?:\\d\\s+){1,}\\d)\\s*([,.])\\s*((?:\\d\\s+){1,}\\d)(?!\\d)");
    protected static final Pattern VIR_RECU_PATTERN =
            Pattern.compile("\\bVIR\\s*[\\./-]?\\s*RECU\\b");
    protected static final Pattern ALNUM_CODE_PREFIX_PATTERN =
            Pattern.compile("^\\s*(?:\\d\\s+)?(?=[0-9A-Z]{5,6}\\b)(?=[0-9A-Z]*\\d)(?=[0-9A-Z]*[A-Z])[0-9A-Z]{5,6}\\b\\s*",
                    Pattern.CASE_INSENSITIVE);

    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "TOTAL", "SOLDE", "FINAL", "CAPITAL", "REPORT", "CUMUL"
    );

    protected final OcrCleaningService cleaningService;
    protected final TransactionClassifier classifier;

    protected AbstractTransactionParser(OcrCleaningService cleaningService, TransactionClassifier classifier) {
        this.cleaningService = cleaningService;
        this.classifier = classifier;
    }

    @Override
    public List<BankTransaction> parse(String text, Integer statementYear) {
        List<BankTransaction> transactions = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return transactions;
        }

        String[] lines = text.split("\n");
        List<String> currentBlock = new ArrayList<>();
        int index = 1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (isForbiddenLine(trimmed)) {
                continue;
            }

            if (isUniversalTransactionStart(trimmed) || isTransactionStart(trimmed)) {
                if (!currentBlock.isEmpty()) {
                    BankTransaction tx = buildTransaction(currentBlock, statementYear, index);
                    if (tx != null) {
                        transactions.add(tx);
                        index++;
                    }
                }
                currentBlock = new ArrayList<>();
                currentBlock.add(trimmed);
                continue;
            }

            if (!currentBlock.isEmpty() && shouldAppendToBlock(trimmed)) {
                currentBlock.add(trimmed);
            }
        }

        if (!currentBlock.isEmpty()) {
            BankTransaction tx = buildTransaction(currentBlock, statementYear, index);
            if (tx != null) {
                transactions.add(tx);
            }
        }

        return transactions;
    }

    protected boolean shouldAppendToBlock(String line) {
        boolean hasDate = DATE_ANY_PATTERN.matcher(line).find();
        boolean hasAmount = AMOUNT_PATTERN.matcher(line).find();
        return !hasDate && !hasAmount;
    }

    protected boolean isUniversalTransactionStart(String line) {
        if (DATE_START_PATTERN.matcher(line).find()) {
            return true;
        }
        return useAwbNumericCodeStart() && AWB_NUMERIC_CODE_START_PATTERN.matcher(line).find();
    }

    protected boolean useAwbNumericCodeStart() {
        return false;
    }

    protected BankTransaction buildTransaction(List<String> block, Integer statementYear, int index) {
        String blockText = String.join(" ", block).toUpperCase();
        if (containsForbiddenKeyword(blockText)) {
            return null;
        }

        TransactionFields fields = extractFields(block, statementYear);
        if (fields == null || fields.dateOperation == null) {
            return null;
        }

        Amounts amounts = extractAmounts(block, blockText);
        if (amounts == null) {
            return null;
        }

        String libelle = buildLibelle(block, fields);
        if (libelle.isBlank()) {
            return null;
        }

        BankTransaction tx = new BankTransaction();
        tx.setRawOcrLine(String.join("\n", block));
        tx.setDateOperation(fields.dateOperation);
        tx.setDateValeur(fields.dateValeur != null ? fields.dateValeur : fields.dateOperation);
        tx.setLibelle(libelle.length() > 1000 ? libelle.substring(0, 1000) : libelle);
        tx.setDebit(amounts.debit);
        tx.setCredit(amounts.credit);
        tx.setSens(tx.getCredit().compareTo(BigDecimal.ZERO) > 0 ? "CREDIT" : "DEBIT");
        tx.setTransactionIndex(index);
        tx.setReference(fields.reference);
        tx.setCode(fields.code);

        var res = classifier.classify(tx.getLibelle(), tx.getSens());
        if (res != null) {
            tx.setCategorie(res.getCategorie());
            tx.setRole(res.getRole());
        }
        tx.setExtractionConfidence(calculateConfidence(tx));
        tx.validate();
        tx.applyAccountingRules();
        return tx;
    }

    protected Amounts extractAmounts(List<String> block, String blockText) {
        List<BigDecimal> values = new ArrayList<>();
        for (String line : block) {
            String sanitizedLine = sanitizeLineForAmountExtraction(line);
            Matcher matcher = AMOUNT_PATTERN.matcher(sanitizedLine);
            while (matcher.find()) {
                BigDecimal value = parseAmount(matcher.group());
                if (value.compareTo(BigDecimal.ZERO) > 0) {
                    values.add(value);
                }
            }
        }

        if (values.isEmpty()) {
            return null;
        }

        if (values.size() >= 2) {
            BigDecimal debit = values.get(values.size() - 2);
            BigDecimal credit = values.get(values.size() - 1);
            return new Amounts(debit, credit);
        }

        BigDecimal single = values.get(0);
        String normalizedBlockText = normalizeHintText(blockText);
        boolean isLikelyCredit = containsHint(blockText, normalizedBlockText, "SALAIRE")
                || containsHint(blockText, normalizedBlockText, "VIREMENT RECU")
                || containsHint(blockText, normalizedBlockText, "VIR RECU")
                || containsHint(blockText, normalizedBlockText, "VIR.RECU")
                || containsHint(blockText, normalizedBlockText, "REMISE")
                || containsHint(blockText, normalizedBlockText, "VERSEMENT")
                || containsHint(blockText, normalizedBlockText, "INTERETS");
        boolean isLikelyDebit = containsHint(blockText, normalizedBlockText, "RETRAIT")
                || containsHint(blockText, normalizedBlockText, "CHEQUE")
                || containsHint(blockText, normalizedBlockText, "PRELEVEMENT")
                || containsHint(blockText, normalizedBlockText, "FRAIS")
                || containsHint(blockText, normalizedBlockText, "COMMISSION")
                || containsHint(blockText, normalizedBlockText, "ACHAT")
                || containsHint(blockText, normalizedBlockText, "PAIEMENT");
        if (VIR_RECU_PATTERN.matcher(blockText).find()) {
            isLikelyCredit = true;
        }

        if (isLikelyCredit && !isLikelyDebit) {
            return new Amounts(BigDecimal.ZERO, single);
        }

        if (isLikelyDebit && !isLikelyCredit) {
            return new Amounts(single, BigDecimal.ZERO);
        }

        return new Amounts(single, BigDecimal.ZERO);
    }

    private boolean containsHint(String raw, String normalizedRaw, String hint) {
        if (raw == null || hint == null || hint.isBlank()) {
            return false;
        }
        if (raw.contains(hint)) {
            return true;
        }
        String normalizedHint = normalizeHintText(hint);
        if (normalizedHint.isBlank()) {
            return false;
        }
        return normalizedRaw.contains(normalizedHint);
    }

    private String normalizeHintText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[^A-Z0-9]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    protected String buildLibelle(List<String> block, TransactionFields fields) {
        StringBuilder builder = new StringBuilder();
        for (String line : block) {
            String cleaned = line;
            cleaned = ALNUM_CODE_PREFIX_PATTERN.matcher(cleaned).replaceFirst(" ");
            cleaned = DATE_ANY_PATTERN.matcher(cleaned).replaceAll(" ");
            cleaned = AMOUNT_PATTERN.matcher(cleaned).replaceAll(" ");
            cleaned = removeRefOrCode(cleaned, fields);
            cleaned = cleaned.replaceAll("(?i)RIB\\b", " ");
            cleaned = cleaned.replaceAll("\\b(?:IBAN|BIC|SWIFT)\\b", " ");
            cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
            if (!cleaned.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(cleaned);
            }
        }
        return builder.toString().trim();
    }

    protected String removeRefOrCode(String line, TransactionFields fields) {
        String cleaned = line;
        if (fields.reference != null) {
            cleaned = cleaned.replace(fields.reference, " ");
        }
        if (fields.code != null) {
            cleaned = cleaned.replace(fields.code, " ");
        }
        return cleaned;
    }

    protected boolean containsForbiddenKeyword(String text) {
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    protected boolean isForbiddenLine(String line) {
        String upper = line.toUpperCase();
        return upper.contains("CAPITAL SOCIAL");
    }

    protected LocalDate parseDate(String d, String m, String y, Integer defaultYear) {
        try {
            int day = Integer.parseInt(d);
            int month = Integer.parseInt(m);
            if (day < 1 || day > 31 || month < 1 || month > 12) {
                return null;
            }
            boolean hasExplicitYear = y != null && !y.isBlank();
            int year = hasExplicitYear ? parseYearToken(y, defaultYear)
                    : (defaultYear != null ? defaultYear : LocalDate.now().getYear());
            if (year == Integer.MIN_VALUE) {
                return null;
            }
            if (year < 1900 || year > 2100) {
                return null;
            }
            if (!hasExplicitYear && defaultYear != null && (year < defaultYear - 2 || year > defaultYear + 2)) {
                return null;
            }
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseYearToken(String token, Integer defaultYear) {
        int parsed = Integer.parseInt(token);
        if (parsed >= 100) {
            return parsed;
        }
        int y1900 = 1900 + parsed;
        int y2000 = 2000 + parsed;
        if (defaultYear != null) {
            return Math.abs(defaultYear - y1900) <= Math.abs(defaultYear - y2000) ? y1900 : y2000;
        }
        return parsed <= 79 ? y2000 : y1900;
    }

    private String sanitizeLineForAmountExtraction(String line) {
        String sanitized = line.replaceAll(
                "(?i)\\bCHEQUE\\s+\\d{1,8}\\s+(?=\\d{1,3}(?:[\\s\\.]\\d{3})*[,.]\\d{2}\\b)",
                "CHEQUE ");
        sanitized = sanitized.replaceAll(
                "(?<!\\d)((?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+))[\\.,]\\s*[\\.,](\\d{2})(?!\\d)",
                "$1,$2");
        sanitized = sanitized.replaceAll(
                "(?<!\\d)((?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+))[\\.,](\\d)(?!\\d)",
                "$1,$20");
        return normalizeExplodedAmounts(sanitized);
    }

    private String normalizeExplodedAmounts(String line) {
        Matcher matcher = EXPLODED_AMOUNT_PATTERN.matcher(line);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String integerPart = matcher.group(1).replaceAll("\\s+", "");
            String separator = matcher.group(2);
            String decimalPart = matcher.group(3).replaceAll("\\s+", "");
            integerPart = sanitizeExplodedIntegerPart(integerPart);
            // Garde-fou: evite les faux montants geants issus d'OCR degrade.
            if (integerPart == null || integerPart.isBlank() || decimalPart.length() < 1 || decimalPart.length() > 2) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            if (decimalPart.length() > 2) {
                decimalPart = decimalPart.substring(0, 2);
            } else if (decimalPart.length() == 1) {
                decimalPart = decimalPart + "0";
            }
            String replacement = integerPart + separator + decimalPart;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String sanitizeExplodedIntegerPart(String integerPart) {
        if (integerPart == null || integerPart.isBlank()) {
            return null;
        }
        if (integerPart.length() <= 7) {
            return integerPart;
        }
        for (int amountLen = 5; amountLen >= 1; amountLen--) {
            if (integerPart.length() <= amountLen) {
                continue;
            }
            int split = integerPart.length() - amountLen;
            if (split < 8) {
                continue;
            }
            String dateToken = integerPart.substring(split - 8, split);
            if (looksLikeCompactDate(dateToken)) {
                return integerPart.substring(split);
            }
        }
        return null;
    }

    private boolean looksLikeCompactDate(String token) {
        if (token == null || !token.matches("\\d{8}")) {
            return false;
        }
        int day = Integer.parseInt(token.substring(0, 2));
        int month = Integer.parseInt(token.substring(2, 4));
        int year = Integer.parseInt(token.substring(4, 8));
        return day >= 1 && day <= 31 && month >= 1 && month <= 12 && year >= 1900 && year <= 2100;
    }

    protected BigDecimal parseAmount(String raw) {
        try {
            return new BigDecimal(cleaningService.normalizeAmount(raw));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    protected double calculateConfidence(BankTransaction tx) {
        double c = 1.0;
        if (tx.getDateOperation() == null) {
            c -= 0.4;
        }
        if (tx.getAbsoluteAmount().compareTo(BigDecimal.ZERO) == 0) {
            c -= 0.3;
        }
        return Math.max(0.1, c);
    }

    protected abstract boolean isTransactionStart(String line);

    protected abstract TransactionFields extractFields(List<String> block, Integer statementYear);

    protected static class TransactionFields {
        public LocalDate dateOperation;
        public LocalDate dateValeur;
        public String reference;
        public String code;
    }

    protected static class Amounts {
        public final BigDecimal debit;
        public final BigDecimal credit;

        public Amounts(BigDecimal debit, BigDecimal credit) {
            this.debit = debit != null ? debit : BigDecimal.ZERO;
            this.credit = credit != null ? credit : BigDecimal.ZERO;
        }
    }
}
