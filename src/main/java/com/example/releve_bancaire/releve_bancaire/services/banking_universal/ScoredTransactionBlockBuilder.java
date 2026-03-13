package com.example.releve_bancaire.releve_bancaire.services.banking_universal;

import com.example.releve_bancaire.releve_bancaire.services.BankType;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class ScoredTransactionBlockBuilder implements TransactionBlockBuilder {

    private static final String MONTH_TOKEN_PATTERN =
            "(?:JAN|JANV|JANVIER|JANUARY|FEB|FEV|FEVR|FEVRIER|FEBRUARY|MAR|MARS|APR|AVR|AVRIL|APRIL|MAY|MAI|"
                    + "JUN|JUIN|JUNE|JUL|JUIL|JUILLET|JULY|AUG|AOUT|AOU|AUGUST|SEP|SEPT|SEPTEMBRE|SEPTEMBER|"
                    + "OCT|OCTOBRE|OCTOBER|NOV|NOVEMBRE|NOVEMBER|DEC|DECEMBRE|DECEMBER)";
    private static final Pattern DATE_FIRST_COLUMN_PATTERN = Pattern.compile(
            "^\\s*(?:0?[1-9]|[12]\\d|3[01])(?:\\s*[\\/\\-.]\\s*|\\s+)(?:0?[1-9]|1[0-2])(?:\\s*[\\/\\-.]\\s*|\\s+\\d{2,4})?\\b");
    private static final Pattern DATE_FIRST_COLUMN_YEAR_FIRST_PATTERN = Pattern.compile(
            "^\\s*(?:19|20)\\d{2}\\s*[\\/\\-.]\\s*(?:0?[1-9]|1[0-2])\\s*[\\/\\-.]\\s*(?:0?[1-9]|[12]\\d|3[01])\\b");
    private static final Pattern DATE_FIRST_COLUMN_TEXTUAL_PATTERN = Pattern.compile(
            "^\\s*(?:0?[1-9]|[12]\\d|3[01])\\s+" + MONTH_TOKEN_PATTERN + "\\.?"
                    + "(?:\\s+(?:19|20)?\\d{2,4})?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWB_CODE_FIRST_COLUMN_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s+)?[0-9A-Z]{5,6}\\s+(?:0?[1-9]|[12]\\d|3[01])\\s+(?:0?[1-9]|1[0-2])\\b");
    private static final Pattern AWB_NUMERIC_CODE_FIRST_COLUMN_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s*){6}\\s+(?:0?[1-9]|[12]\\d|3[01])\\s+(?:0?[1-9]|1[0-2])\\b");
    private static final Pattern AWB_STRICT_6_DIGITS_FIRST_COLUMN_PATTERN = Pattern.compile("^\\s*\\d{6}\\b");
    private static final Pattern FOOTER_TOTALS_NUMERIC_LINE_PATTERN = Pattern.compile(
            "^(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,\\.]\\d{2}\\s+(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,\\.]\\d{2}$");
    private static final Pattern DECIMAL_AMOUNT_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,.]\\d{2}(?!\\d)");
    private static final Pattern EXPLODED_AMOUNT_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\d\\s+){2,}\\d\\s*[,\\.]\\s*(?:\\d\\s+)+\\d(?!\\d)");
    private static final Pattern COMPACT_CODE_DATE_PATTERN = Pattern.compile(
            "^\\d?[0-9A-Z]{5,6}(?:0[1-9]|[12]\\d|3[01])(?:0[1-9]|1[0-2]).*");

    private final BankLayoutProfileRegistry profileRegistry;

    public ScoredTransactionBlockBuilder(BankLayoutProfileRegistry profileRegistry) {
        this.profileRegistry = profileRegistry;
    }

    @Override
    public List<TransactionBlock> buildBlocks(String text, TransactionExtractionContext context) {
        List<TransactionBlock> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }

        BankType bankType = context.bankType() != null ? context.bankType() : BankType.UNKNOWN;
        String[] rawLines = text.split("\n");
        boolean tableStarted = false;
        TransactionBlock currentBlock = null;

        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i] == null ? "" : rawLines[i].trim();
            if (line.isBlank()) {
                continue;
            }

            String normalized = line.replaceAll("\\s{2,}", " ").trim();
            if (normalized.isBlank()) {
                continue;
            }

            String upper = normalized.toUpperCase();
            String normalizedUpper = normalizeAsciiUpper(upper);
            if (isPageBoundaryLine(normalizedUpper)) {
                if (currentBlock != null && !currentBlock.getLines().isEmpty()) {
                    blocks.add(currentBlock);
                    currentBlock = null;
                }
                tableStarted = false;
                continue;
            }
            if (isTableHeaderLine(normalizedUpper)) {
                if (currentBlock != null && !currentBlock.getLines().isEmpty()) {
                    blocks.add(currentBlock);
                    currentBlock = null;
                }
                tableStarted = true;
                continue;
            }
            if (isIgnoredBoundaryLine(normalizedUpper)) {
                if (currentBlock != null && !currentBlock.getLines().isEmpty()) {
                    blocks.add(currentBlock);
                    currentBlock = null;
                }
                tableStarted = false;
                continue;
            }

            boolean isStartLine = isTransactionStartLine(normalized, bankType);
            if (!tableStarted && !isStartLine) {
                continue;
            }
            if (isStartLine) {
                tableStarted = true;
                if (currentBlock != null && !currentBlock.getLines().isEmpty()) {
                    blocks.add(currentBlock);
                }
                currentBlock = new TransactionBlock(i + 1);
                currentBlock.addLine(normalized);
                continue;
            }

            if (tableStarted
                    && currentBlock != null
                    && shouldAppendContinuationLine(normalized)) {
                currentBlock.addLine(normalized);
            }
        }

        if (currentBlock != null && !currentBlock.getLines().isEmpty()) {
            blocks.add(currentBlock);
        }

        return blocks;
    }

    private boolean isPageBoundaryLine(String normalizedUpperLine) {
        return normalizedUpperLine.startsWith("--- PAGE ");
    }

    private boolean isTransactionStartLine(String line, BankType bankType) {
        String normalizedLine = normalizeAsciiUpper(line == null ? "" : line);
        boolean startsWithDate = DATE_FIRST_COLUMN_PATTERN.matcher(line).find()
                || DATE_FIRST_COLUMN_YEAR_FIRST_PATTERN.matcher(line).find()
                || DATE_FIRST_COLUMN_TEXTUAL_PATTERN.matcher(normalizedLine).find();
        boolean startsWithAwbCode = AWB_CODE_FIRST_COLUMN_PATTERN.matcher(line).find()
                || AWB_NUMERIC_CODE_FIRST_COLUMN_PATTERN.matcher(line).find()
                || AWB_STRICT_6_DIGITS_FIRST_COLUMN_PATTERN.matcher(line).find();
        boolean startsWithExplodedCodeDate = looksLikeExplodedCodeDateStart(normalizedLine);

        if (bankType == BankType.UNKNOWN) {
            return startsWithDate || startsWithAwbCode || startsWithExplodedCodeDate;
        }
        return startsWithDate || startsWithAwbCode || startsWithExplodedCodeDate;
    }

    private boolean isIgnoredBoundaryLine(String normalizedUpperLine) {
        return FOOTER_TOTALS_NUMERIC_LINE_PATTERN.matcher(normalizedUpperLine).matches()
                || normalizedUpperLine.startsWith("REPORT")
                || normalizedUpperLine.contains("TOTAL MOUVEMENTS")
                || normalizedUpperLine.contains("TOTAL DES MOUVEMENTS")
                || normalizedUpperLine.contains("TOTAUX DES MOUVEMENTS")
                || normalizedUpperLine.contains("SOLDE FINAL")
                || normalizedUpperLine.contains("SOLDE DEPART")
                || normalizedUpperLine.contains("NOUVEAU SOLDE")
                || normalizedUpperLine.startsWith("(1) DEPUIS")
                || normalizedUpperLine.contains("TARIF AU")
                || normalizedUpperLine.startsWith("PAGE ")
                || normalizedUpperLine.contains("COORDONNEES BANCAIRES")
                || normalizedUpperLine.contains("RELEVE IDENTITE BANCAIRE")
                || normalizedUpperLine.contains("CAPITAL SOCIAL")
                || normalizedUpperLine.contains("SOCIETE ANONYME AU CAPITAL")
                || normalizedUpperLine.contains("AGREEE EN QUALITE D'ETABLISSEMENT")
                || normalizedUpperLine.contains("C.N.S.S")
                || normalizedUpperLine.contains("RCS PARIS")
                || normalizedUpperLine.contains("SIEGE SOCIAL")
                || normalizedUpperLine.contains("RC ")
                || normalizedUpperLine.contains("ICE ");
    }

    private boolean isTableHeaderLine(String normalizedUpperLine) {
        if (normalizedUpperLine.matches("^[_\\s\\-]+$")) {
            return true;
        }
        if (normalizedUpperLine.startsWith("DATE OP")
                || normalizedUpperLine.startsWith("DATE VALEUR")
                || normalizedUpperLine.startsWith("DATE VAL")
                || normalizedUpperLine.startsWith("NATURE DE L'OPERATION")) {
            return true;
        }
        boolean looksLikeColumns = normalizedUpperLine.contains("LIBELLE")
                && normalizedUpperLine.contains("DEBIT")
                && normalizedUpperLine.contains("CREDIT")
                && !DECIMAL_AMOUNT_PATTERN.matcher(normalizedUpperLine).find();
        return looksLikeColumns;
    }

    private boolean looksLikeExplodedCodeDateStart(String normalizedLine) {
        if (normalizedLine == null || normalizedLine.isBlank()) {
            return false;
        }
        if (!hasAmountCandidate(normalizedLine)) {
            return false;
        }
        String compact = normalizedLine.replaceAll("\\s+", "");
        if (compact.length() < 12) {
            return false;
        }
        return COMPACT_CODE_DATE_PATTERN.matcher(compact).find();
    }

    private boolean hasAmountCandidate(String line) {
        return DECIMAL_AMOUNT_PATTERN.matcher(line).find()
                || EXPLODED_AMOUNT_PATTERN.matcher(line).find();
    }

    private boolean shouldAppendContinuationLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        if (DATE_FIRST_COLUMN_PATTERN.matcher(line).find()
                || DATE_FIRST_COLUMN_YEAR_FIRST_PATTERN.matcher(line).find()
                || DATE_FIRST_COLUMN_TEXTUAL_PATTERN.matcher(normalizeAsciiUpper(line)).find()
                || AWB_CODE_FIRST_COLUMN_PATTERN.matcher(line).find()
                || AWB_NUMERIC_CODE_FIRST_COLUMN_PATTERN.matcher(line).find()
                || AWB_STRICT_6_DIGITS_FIRST_COLUMN_PATTERN.matcher(line).find()) {
            return false;
        }
        if (!line.matches(".*[A-Za-z].*")) {
            return false;
        }

        // Ignore les lignes isolées de type "FACTURE 99887766" (référence seule).
        String[] tokens = line.replaceAll("\\s{2,}", " ").trim().split(" ");
        int alphaTokens = 0;
        int longNumericTokens = 0;
        for (String token : tokens) {
            String cleaned = token.replaceAll("[^A-Za-z0-9]", "");
            if (cleaned.isBlank()) {
                continue;
            }
            if (cleaned.matches(".*[A-Za-z].*")) {
                alphaTokens++;
            }
            if (cleaned.matches("\\d{4,}")) {
                longNumericTokens++;
            }
        }

        return !(alphaTokens <= 1 && longNumericTokens >= 1);
    }

    private String normalizeAsciiUpper(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase();
    }
}
