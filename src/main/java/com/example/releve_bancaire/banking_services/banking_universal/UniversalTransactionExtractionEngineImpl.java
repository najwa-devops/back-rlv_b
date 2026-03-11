package com.example.releve_bancaire.banking_services.banking_universal;

import com.example.releve_bancaire.banking_entity.BankTransaction;
import com.example.releve_bancaire.banking_services.TransactionClassifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UniversalTransactionExtractionEngineImpl implements UniversalTransactionExtractionEngine {

    private static final String MONTH_TOKEN_PATTERN =
            "(?:JAN|JANV|JANVIER|JANUARY|FEB|FEV|FEVR|FEVRIER|FEBRUARY|MAR|MARS|APR|AVR|AVRIL|APRIL|MAY|MAI|"
                    + "JUN|JUIN|JUNE|JUL|JUIL|JUILLET|JULY|AUG|AOUT|AOU|AUGUST|SEP|SEPT|SEPTEMBRE|SEPTEMBER|"
                    + "OCT|OCTOBRE|OCTOBER|NOV|NOVEMBRE|NOVEMBER|DEC|DECEMBRE|DECEMBER)";
    private static final Pattern DATE_ANY_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})(?:\\s*[\\/\\-.\\s]\\s*(\\d{2,4}))?(?!\\d)");
    private static final Pattern DATE_YEAR_FIRST_PATTERN =
            Pattern.compile("(?<!\\d)((?:19|20)\\d{2})\\s*[\\/\\-.]\\s*(\\d{1,2})\\s*[\\/\\-.]\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern DATE_TEXTUAL_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2})\\s+(" + MONTH_TOKEN_PATTERN + ")\\.?(?:\\s+((?:19|20)?\\d{2,4}))?(?!\\d)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern START_TWO_DATES_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s+)?(?:[0-9A-Z]{4,10}\\s+)?"
                    + "(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})(?:\\s*[\\/\\-.\\s]\\s*(\\d{2,4}))?"
                    + "\\s+(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})(?:\\s*[\\/\\-.\\s]\\s*(\\d{2,4}))?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern START_ONE_DATE_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s+)?(?:[0-9A-Z]{4,10}\\s+)?"
                    + "(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})(?:\\s*[\\/\\-.\\s]\\s*(\\d{2,4}))?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern START_ONE_DATE_YEAR_FIRST_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s+)?(?:[0-9A-Z]{4,10}\\s+)?"
                    + "((?:19|20)\\d{2})\\s*[\\/\\-.]\\s*(\\d{1,2})\\s*[\\/\\-.]\\s*(\\d{1,2})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern START_ONE_TEXTUAL_DATE_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s+)?(?:[0-9A-Z]{4,10}\\s+)?"
                    + "(\\d{1,2})\\s+(" + MONTH_TOKEN_PATTERN + ")\\.?(?:\\s+((?:19|20)?\\d{2,4}))?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_DATE_BEFORE_AMOUNT_PATTERN = Pattern.compile(
            "(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})(?:\\s*[\\/\\-.\\s]\\s*(\\d{2,4}))?"
                    + "\\s+(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,\\.]\\d{2}\\s*$");
    private static final Pattern TRAILING_YEAR_FIRST_DATE_BEFORE_AMOUNT_PATTERN = Pattern.compile(
            "((?:19|20)\\d{2})\\s*[\\/\\-.]\\s*(\\d{1,2})\\s*[\\/\\-.]\\s*(\\d{1,2})"
                    + "\\s+(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,\\.]\\d{2}\\s*$");
    private static final Pattern TRAILING_TEXTUAL_DATE_BEFORE_AMOUNT_PATTERN = Pattern.compile(
            "(\\d{1,2})\\s+(" + MONTH_TOKEN_PATTERN + ")\\.?(?:\\s+((?:19|20)?\\d{2,4}))?"
                    + "\\s+(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,\\.]\\d{2}\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPACT_AWB_DAY_MONTH_PATTERN = Pattern.compile(
            "^\\s*[0-9A-Z]{5,6}(\\d{2})(\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPACT_FULL_DATE_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{2})(\\d{2})(\\d{4})(?!\\d)");
    private static final Pattern DECIMAL_AMOUNT_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,.]\\d{2}(?!\\d)");
    private static final Pattern AWB_PREFIX_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s+)?[0-9A-Z]{5,6}\\s+(?:\\d{1,2}\\s+\\d{1,2})\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWB_NUMERIC_CODE_PREFIX_PATTERN = Pattern.compile("^\\s*(?:\\d\\s*){6}\\s+");
    private static final Pattern ALNUM_CODE_PREFIX_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s+)?(?=[0-9A-Z]{5,6}\\b)(?=[0-9A-Z]*\\d)(?=[0-9A-Z]*[A-Z])[0-9A-Z]{5,6}\\b\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWB_COMPACT_PREFIX_PATTERN = Pattern.compile(
            "^\\s*[0-9A-Z]{5,6}(?:\\d{2})(?:\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private final TransactionBlockBuilder blockBuilder;
    private final NumericClassifier numericClassifier;
    private final BalanceDrivenResolver balanceDrivenResolver;
    private final TransactionConfidenceScorer confidenceScorer;
    private final TransactionClassifier classifier;

    public UniversalTransactionExtractionEngineImpl(
            TransactionBlockBuilder blockBuilder,
            NumericClassifier numericClassifier,
            BalanceDrivenResolver balanceDrivenResolver,
            TransactionConfidenceScorer confidenceScorer,
            TransactionClassifier classifier) {
        this.blockBuilder = blockBuilder;
        this.numericClassifier = numericClassifier;
        this.balanceDrivenResolver = balanceDrivenResolver;
        this.confidenceScorer = confidenceScorer;
        this.classifier = classifier;
    }

    @Override
    public List<BankTransaction> extract(String cleanedText, TransactionExtractionContext context) {
        List<BankTransaction> results = new ArrayList<>();
        List<TransactionBlock> blocks = blockBuilder.buildBlocks(cleanedText, context);
        int index = 1;

        for (TransactionBlock block : blocks) {
            BankTransaction tx = parseBlock(block, context, index);
            if (tx != null) {
                results.add(tx);
                index++;
            }
        }

        balanceDrivenResolver.resolve(results);

        for (BankTransaction tx : results) {
            int score = confidenceScorer.score(tx);
            tx.setConfidenceScore(score);
            tx.setExtractionConfidence(score / 100.0d);
            tx.setNeedsReview(score < 60 || !tx.getFlags().isEmpty());
            tx.setIsValid(score >= 50);
            if (!tx.getFlags().isEmpty()) {
                tx.setExtractionErrors(String.join(",", tx.getFlags()));
            }
        }

        return results;
    }

    private BankTransaction parseBlock(TransactionBlock block, TransactionExtractionContext context, int index) {
        if (block.getLines().isEmpty()) {
            return null;
        }

        ExtractedDates dates = extractDates(block.getLines(), context.statementYear(), context.statementMonth());
        String description = extractDescription(block.getLines());
        NumericClassifier.NumericClassification numeric = numericClassifier.classify(block.getLines(), description, context);

        BankTransaction tx = new BankTransaction();
        tx.setRawOcrLine(String.join("\n", block.getLines()));
        tx.setLineNumber(block.getStartLineNumber());
        tx.setTransactionIndex(index);
        tx.setDateOperation(dates.dateOperation());
        tx.setDateValeur(dates.dateValeur());
        tx.setLibelle(description.isBlank() ? "DESCRIPTION_INCONNUE" : truncate(description, 1000));
        tx.setDebit(nullSafe(numeric.debit()));
        tx.setCredit(nullSafe(numeric.credit()));
        tx.setBalance(numeric.balance());
        tx.setSens(tx.getCredit().compareTo(BigDecimal.ZERO) > 0 ? "CREDIT" : "DEBIT");
        tx.setFlags(new ArrayList<>(numeric.flags()));

        if (tx.getDebit().add(tx.getCredit()).compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        if (dates.inferredDate()) {
            tx.getFlags().add("DATE_INFERRED");
        }
        if (looksLikeReferenceOnly(description)) {
            tx.getFlags().add("DESCRIPTION_REFERENCE_HEAVY");
        }

        var classification = classifier.classify(tx.getLibelle(), tx.getSens());
        if (classification != null) {
            tx.setCategorie(classification.getCategorie());
            tx.setRole(classification.getRole());
        }
        tx.applyAccountingRules();
        return tx;
    }

    private ExtractedDates extractDates(List<String> lines, Integer statementYear, Integer statementMonth) {
        LocalDate op = null;
        LocalDate val = null;
        Integer referenceYear = statementYear;

        for (String line : lines) {
            String normalizedLine = compactSingleCharTokenSpacing(line);
            String start = sanitizeForStartDateParsing(normalizedLine);
            Matcher twoDates = START_TWO_DATES_PATTERN.matcher(start);
            if (twoDates.find()) {
                String opYearToken = twoDates.group(3);
                String valYearToken = sanitizeYearTokenFromAmountContinuation(start, twoDates, 6);
                LocalDate opCandidate = parseDate(twoDates.group(1), twoDates.group(2), opYearToken, referenceYear);
                Integer opYear = extractParsedYear(opYearToken, referenceYear);
                LocalDate valCandidate = parseDate(twoDates.group(4), twoDates.group(5), valYearToken, opYear);
                if (opCandidate != null) {
                    op = opCandidate;
                    referenceYear = opCandidate.getYear();
                }
                if (valCandidate != null) {
                    val = valCandidate;
                }
                break;
            }
            Matcher oneDate = START_ONE_DATE_PATTERN.matcher(start);
            if (oneDate.find()) {
                LocalDate opCandidate = parseDate(oneDate.group(1), oneDate.group(2), oneDate.group(3), referenceYear);
                if (opCandidate != null) {
                    op = opCandidate;
                    val = resolveTrailingValueDate(line, opCandidate, referenceYear);
                    break;
                }
            }
            Matcher oneDateYearFirst = START_ONE_DATE_YEAR_FIRST_PATTERN.matcher(start);
            if (oneDateYearFirst.find()) {
                LocalDate opCandidate = parseDate(oneDateYearFirst.group(3), oneDateYearFirst.group(2),
                        oneDateYearFirst.group(1), referenceYear);
                if (opCandidate != null) {
                    op = opCandidate;
                    val = resolveTrailingValueDate(line, opCandidate, referenceYear);
                    break;
                }
            }
            Matcher oneDateTextual = START_ONE_TEXTUAL_DATE_PATTERN.matcher(start);
            if (oneDateTextual.find()) {
                LocalDate opCandidate = parseTextualDate(
                        oneDateTextual.group(1), oneDateTextual.group(2), oneDateTextual.group(3), referenceYear);
                if (opCandidate != null) {
                    op = opCandidate;
                    val = resolveTrailingValueDate(line, opCandidate, referenceYear);
                    break;
                }
            }
            Matcher compactAwb = COMPACT_AWB_DAY_MONTH_PATTERN.matcher(start);
            if (compactAwb.find()) {
                LocalDate opCandidate = parseDate(compactAwb.group(1), compactAwb.group(2), null, referenceYear);
                if (opCandidate != null) {
                    op = opCandidate;
                    val = resolveCompactValueDate(normalizedLine, opCandidate, referenceYear);
                    break;
                }
            }
        }

        if (op != null) {
            if (val == null) {
                val = op;
            }
            return new ExtractedDates(op, val, false);
        }

        // Fallback ultime: scanner toutes les dates dans le bloc.
        List<LocalDate> found = new ArrayList<>();
        for (String line : lines) {
            String normalizedLine = compactSingleCharTokenSpacing(line);
            Matcher yearFirst = DATE_YEAR_FIRST_PATTERN.matcher(normalizedLine);
            while (yearFirst.find()) {
                LocalDate date = parseDate(yearFirst.group(3), yearFirst.group(2), yearFirst.group(1), statementYear);
                if (date != null) {
                    found.add(date);
                }
            }
            Matcher textual = DATE_TEXTUAL_PATTERN.matcher(normalizedLine);
            while (textual.find()) {
                LocalDate date = parseTextualDate(textual.group(1), textual.group(2), textual.group(3), statementYear);
                if (date != null) {
                    found.add(date);
                }
            }
            Matcher matcher = DATE_ANY_PATTERN.matcher(normalizedLine);
            while (matcher.find()) {
                LocalDate date = parseDate(matcher.group(1), matcher.group(2), matcher.group(3), statementYear);
                if (date != null) {
                    found.add(date);
                }
            }
            Matcher compact = COMPACT_FULL_DATE_PATTERN.matcher(normalizedLine);
            while (compact.find()) {
                LocalDate date = parseDate(compact.group(1), compact.group(2), compact.group(3), statementYear);
                if (date != null) {
                    found.add(date);
                }
            }
        }
        if (!found.isEmpty()) {
            return new ExtractedDates(found.get(0), found.size() > 1 ? found.get(1) : found.get(0), false);
        }

        int year = statementYear != null ? statementYear : LocalDate.now().getYear();
        int month = statementMonth != null ? statementMonth : 1;
        return new ExtractedDates(LocalDate.of(year, month, 1), LocalDate.of(year, month, 1), true);
    }

    private LocalDate resolveTrailingValueDate(String sourceLine, LocalDate opDate, Integer referenceYear) {
        if (sourceLine == null || sourceLine.isBlank()) {
            return opDate;
        }
        Matcher trailingYearFirst = TRAILING_YEAR_FIRST_DATE_BEFORE_AMOUNT_PATTERN.matcher(sourceLine);
        if (trailingYearFirst.find()) {
            LocalDate parsed = parseDate(trailingYearFirst.group(3), trailingYearFirst.group(2),
                    trailingYearFirst.group(1), referenceYear);
            return parsed != null ? parsed : opDate;
        }
        Matcher trailingTextual = TRAILING_TEXTUAL_DATE_BEFORE_AMOUNT_PATTERN.matcher(sourceLine);
        if (trailingTextual.find()) {
            LocalDate parsed = parseTextualDate(trailingTextual.group(1), trailingTextual.group(2),
                    trailingTextual.group(3), referenceYear);
            return parsed != null ? parsed : opDate;
        }
        Matcher trailing = TRAILING_DATE_BEFORE_AMOUNT_PATTERN.matcher(sourceLine);
        if (!trailing.find()) {
            return opDate;
        }
        String trailingYearToken = sanitizeYearTokenFromAmountContinuation(sourceLine, trailing, 3);
        LocalDate parsed = parseDate(trailing.group(1), trailing.group(2), trailingYearToken, referenceYear);
        if (parsed == null && trailingYearToken != null) {
            parsed = parseDate(trailing.group(1), trailing.group(2), null, referenceYear);
        }
        return parsed != null ? parsed : opDate;
    }

    private String sanitizeYearTokenFromAmountContinuation(String source, Matcher matcher, int yearGroupIndex) {
        if (source == null || matcher == null) {
            return null;
        }
        String token = matcher.group(yearGroupIndex);
        if (token == null || token.isBlank() || token.length() != 2) {
            return token;
        }
        int tokenEnd = matcher.end(yearGroupIndex);
        if (tokenEnd < 0 || tokenEnd >= source.length()) {
            return token;
        }
        String tail = source.substring(tokenEnd);
        // Avoid treating amount fragments like "54 000,00" as a year token "54".
        if (tail.matches("^\\s*(?:[\\s\\.]?\\d{3})(?:[\\s\\.]\\d{3})*[,.]\\d{2}.*$")) {
            return null;
        }
        return token;
    }

    private LocalDate parseTextualDate(String dayToken, String monthToken, String yearToken, Integer defaultYear) {
        Integer month = parseMonthToken(monthToken);
        if (month == null) {
            return null;
        }
        return parseDate(dayToken, String.valueOf(month), yearToken, defaultYear);
    }

    private Integer parseMonthToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = normalizeAsciiUpper(token).replace(".", "");
        return switch (normalized) {
            case "JAN", "JANV", "JANVIER", "JANUARY" -> 1;
            case "FEB", "FEV", "FEVR", "FEVRIER", "FEBRUARY" -> 2;
            case "MAR", "MARS" -> 3;
            case "APR", "AVR", "AVRIL", "APRIL" -> 4;
            case "MAY", "MAI" -> 5;
            case "JUN", "JUIN", "JUNE" -> 6;
            case "JUL", "JUIL", "JUILLET", "JULY" -> 7;
            case "AUG", "AOU", "AOUT", "AUGUST" -> 8;
            case "SEP", "SEPT", "SEPTEMBRE", "SEPTEMBER" -> 9;
            case "OCT", "OCTOBRE", "OCTOBER" -> 10;
            case "NOV", "NOVEMBRE", "NOVEMBER" -> 11;
            case "DEC", "DECEMBRE", "DECEMBER" -> 12;
            default -> null;
        };
    }

    private LocalDate parseDate(String d, String m, String y, Integer defaultYear) {
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

    private String sanitizeForStartDateParsing(String line) {
        if (line == null) {
            return "";
        }
        String out = line.trim();
        out = AWB_NUMERIC_CODE_PREFIX_PATTERN.matcher(out).replaceFirst("");
        return out;
    }

    private Integer extractParsedYear(String y, Integer fallback) {
        if (y == null || y.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(y);
            if (parsed < 100) {
                parsed += 2000;
            }
            if (parsed >= 1900 && parsed <= 2100) {
                return parsed;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private String extractDescription(List<String> lines) {
        List<String> sanitized = new ArrayList<>();
        for (String line : lines) {
            String current = compactSingleCharTokenSpacing(line == null ? "" : line.trim());
            if (current.isBlank()) {
                continue;
            }
            current = AWB_COMPACT_PREFIX_PATTERN.matcher(current).replaceFirst(" ");
            current = AWB_PREFIX_PATTERN.matcher(current).replaceFirst("");
            sanitized.add(current);
        }

        String text = String.join(" ", sanitized);
        text = DATE_YEAR_FIRST_PATTERN.matcher(text).replaceAll(" ");
        text = DATE_TEXTUAL_PATTERN.matcher(text).replaceAll(" ");
        text = DATE_ANY_PATTERN.matcher(text).replaceAll(" ");
        text = COMPACT_FULL_DATE_PATTERN.matcher(text).replaceAll(" ");
        // Normalise les montants OCR avec double séparateur (ex: "8442, ,50" ou "263902, ,50" → "8442,50")
        // pour que DECIMAL_AMOUNT_PATTERN puisse les supprimer ensuite.
        text = text.replaceAll("(?<!\\d)(\\d+)[,\\.]\\s+[,\\.](\\d{1,2})(?!\\d)", "$1,$2");
        text = DECIMAL_AMOUNT_PATTERN.matcher(text).replaceAll(" ");
        // Supprime les fragments de montants résiduels non capturés (ex: "44177," "104.800,")
        // qui proviennent d'une coupure OCR d'un montant entre deux lignes.
        text = text.replaceAll("(?<!\\d)(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d{3,})[,\\.]\\s*", " ");
        // Supprime les formats horaires OCR type "18H17", "12H11" avant les autres nettoyages.
        text = text.replaceAll("(?i)\\b\\d{1,2}H\\d{2}\\b", " ");
        text = text.replaceAll("(?i)RIB\\b", " ");
        text = text.replaceAll("\\b(?:IBAN|BIC|SWIFT)\\b", " ");
        text = ALNUM_CODE_PREFIX_PATTERN.matcher(text).replaceFirst(" ");
        // Supprime les codes de référence numériques purs en début de libellé
        // (ex: BCP "484474 VIR. RECU..." → "VIR. RECU...").
        text = text.replaceAll("^\\s*\\d{4,8}\\s+(?=[A-Z])", "");
        // Supprime le préfixe /MM (ex: "/02 ") en début de libellé — artefact Saham Bank et similaires
        // où le mois de l'opération est encodé sous forme "/02 LIBELLE..." dans la colonne description.
        // Le \\s* initial absorbe les espaces éventuels avant le slash.
        text = text.replaceAll("^\\s*/\\d{1,2}\\s+", "");
        // Supprime les fragments décimaux résiduels (ex: ",00", ",90") laissés après suppression de la partie entière.
        text = text.replaceAll("(?<=\\s|^)[,\\.]\\d{1,2}(?=\\s|$)", " ");
        return text.replaceAll("\\s{2,}", " ").trim();
    }

    private boolean looksLikeReferenceOnly(String description) {
        if (description == null || description.isBlank()) {
            return true;
        }
        String compact = description.replaceAll("\\s+", "");
        return compact.matches("[A-Z0-9\\-_/]+");
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    private BigDecimal nullSafe(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private LocalDate resolveCompactValueDate(String compactLine, LocalDate opDate, Integer referenceYear) {
        if (compactLine == null || compactLine.isBlank()) {
            return opDate;
        }
        LocalDate last = null;
        Matcher compact = COMPACT_FULL_DATE_PATTERN.matcher(compactLine);
        while (compact.find()) {
            LocalDate candidate = parseDate(compact.group(1), compact.group(2), compact.group(3), referenceYear);
            if (candidate != null) {
                last = candidate;
            }
        }
        return last != null ? last : opDate;
    }

    private String compactSingleCharTokenSpacing(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        // Convertit "A W B G A B" -> "AWBGAB" sans toucher "0 016MI" ni "3 800,00".
        return line.replaceAll("(?<=\\b[0-9A-Z])\\s+(?=[0-9A-Z]\\b)", "");
    }

    private String normalizeAsciiUpper(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase();
    }

    private record ExtractedDates(LocalDate dateOperation, LocalDate dateValeur, boolean inferredDate) {
    }
}
