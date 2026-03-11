package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.banking_entity.BankTransaction;
import com.example.releve_bancaire.banking_services.banking_ocr.OcrCleaningService;
import com.example.releve_bancaire.banking_services.banking_universal.TransactionExtractionContext;
import com.example.releve_bancaire.banking_services.banking_universal.UniversalTransactionExtractionEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class TransactionExtractorService {
    private static final Pattern YEAR_4_PATTERN = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)");
    private static final Pattern AMOUNT_LAYOUT_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,.]\\d{2}(?!\\d)");
    /** Nombre minimum de lignes avec espace de colonisation significatif pour conclure layout deux-colonnes. */
    private static final int TWO_COLUMN_DETECTION_THRESHOLD = 3;
    /** Nombre minimum de caractères d'espace après un montant pour considérer qu'une colonne Crédit est vide. */
    private static final int TRAILING_COL_SPACE_MIN = 8;
    private static final Pattern START_LINE_DATE_WITH_YEAR_PATTERN = Pattern.compile(
            "^\\s*(?:\\d+\\s+)?(?:[0-9A-Z]{4,10}\\s+)?"
                    + "(\\d{1,2})\\s*[\\/\\-.]\\s*(\\d{1,2})\\s*(?:[\\/\\-.]|\\s+)\\s*(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD_DU_AU_PATTERN = Pattern.compile(
            "\\bDU\\s+(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{2,4})\\s+AU\\s+"
                    + "(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{2,4})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern START_DAY_MONTH_PATTERN = Pattern.compile(
            "^\\s*(?:\\d\\s+)?(?:[0-9A-Z]{4,10}\\s+)?(\\d{1,2})\\s*[\\/\\-.\\s]\\s*(\\d{1,2})\\b",
            Pattern.CASE_INSENSITIVE);

    private final OcrCleaningService cleaningService;
    private final BankDetector bankDetector;
    private final HeaderFooterCleaner headerFooterCleaner;
    private final TransactionParserFactory parserFactory; // Conservé pour compatibilité constructeur/tests
    private final UniversalTransactionExtractionEngine universalEngine;

    @Autowired
    public TransactionExtractorService(OcrCleaningService cleaningService, BankDetector bankDetector,
            HeaderFooterCleaner headerFooterCleaner, TransactionParserFactory parserFactory,
            UniversalTransactionExtractionEngine universalEngine) {
        this.cleaningService = cleaningService;
        this.bankDetector = bankDetector;
        this.headerFooterCleaner = headerFooterCleaner;
        this.parserFactory = parserFactory;
        this.universalEngine = universalEngine;
    }

    public TransactionExtractorService(OcrCleaningService cleaningService, BankDetector bankDetector,
            HeaderFooterCleaner headerFooterCleaner, TransactionParserFactory parserFactory) {
        this.cleaningService = cleaningService;
        this.bankDetector = bankDetector;
        this.headerFooterCleaner = headerFooterCleaner;
        this.parserFactory = parserFactory;
        this.universalEngine = (text, context) -> parserFactory.getParser(context.bankType()).parse(text, context.statementYear());
    }

    public List<BankTransaction> extractTransactions(String ocrText, Integer statementMonth, Integer statementYear,
            String manualBankType) {
        if (ocrText == null || ocrText.isBlank()) {
            return new ArrayList<>();
        }

        BankType bankType = bankDetector.detectBankType(ocrText);

        if (manualBankType != null && !manualBankType.equalsIgnoreCase("AUTO")) {
            BankType manualType = mapManualType(manualBankType);
            if (manualType != null) {
                bankType = manualType;
                log.info("Using manual bank type: {}", bankType);
            } else {
                log.warn("Unknown manual bank type: {}", manualBankType);
            }
        }

        String cleanedText = cleaningService.cleanOcrText(ocrText);
        String textWithoutHeaderFooter = headerFooterCleaner.removeHeaderFooter(cleanedText);
        Integer resolvedYear = resolveStatementYear(statementYear, textWithoutHeaderFooter, cleanedText);
        Integer resolvedMonth = resolveStatementMonth(statementMonth, textWithoutHeaderFooter, cleanedText);

        boolean twoColumnLayout = detectTwoColumnAmountLayout(textWithoutHeaderFooter);
        log.info("Layout détecté pour {} : {}", bankType, twoColumnLayout ? "DEUX_COLONNES" : "UNE_COLONNE");

        List<BankTransaction> extracted = universalEngine.extract(
                textWithoutHeaderFooter,
                new TransactionExtractionContext(bankType, resolvedMonth, resolvedYear, twoColumnLayout));

        if (!extracted.isEmpty()) {
            return extracted;
        }

        log.warn("Universal extractor returned 0 transactions for bank {}. Falling back to parserFactory.", bankType);
        var fallbackParser = parserFactory.getParser(bankType);
        List<BankTransaction> fallback = fallbackParser.parse(textWithoutHeaderFooter, resolvedYear);

        if (!fallback.isEmpty()) {
            return fallback;
        }

        // Second fallback on full cleaned text if header/footer cleanup was too aggressive
        return fallbackParser.parse(cleanedText, resolvedYear);
    }

    private Integer resolveStatementYear(Integer providedYear, String... texts) {
        Map<Integer, Integer> explicitYearFrequencies = new HashMap<>();
        Map<Integer, Integer> fallbackYearFrequencies = new HashMap<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String[] lines = text.split("\n");
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Matcher startWithYear = START_LINE_DATE_WITH_YEAR_PATTERN.matcher(trimmed);
                if (startWithYear.find()) {
                    Integer year = parseYearToken(startWithYear.group(3), providedYear);
                    if (year != null) {
                        explicitYearFrequencies.merge(year, 1, Integer::sum);
                    }
                }

                Matcher period = PERIOD_DU_AU_PATTERN.matcher(trimmed);
                if (period.find()) {
                    Integer yearStart = parseYearToken(period.group(3), providedYear);
                    Integer yearEnd = parseYearToken(period.group(6), providedYear);
                    if (yearStart != null) {
                        explicitYearFrequencies.merge(yearStart, 2, Integer::sum);
                    }
                    if (yearEnd != null) {
                        explicitYearFrequencies.merge(yearEnd, 2, Integer::sum);
                    }
                }
            }

            Matcher matcher = YEAR_4_PATTERN.matcher(text);
            while (matcher.find()) {
                int year = Integer.parseInt(matcher.group(1));
                fallbackYearFrequencies.merge(year, 1, Integer::sum);
            }
        }

        Integer dominantExplicitYear = explicitYearFrequencies.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(null);

        Integer dominantFallbackYear = fallbackYearFrequencies.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(null);

        Integer detectedYear = dominantExplicitYear != null ? dominantExplicitYear : dominantFallbackYear;

        if (providedYear != null && providedYear >= 1900 && providedYear <= 2100) {
            if (detectedYear != null) {
                boolean explicitConflict = dominantExplicitYear != null && !dominantExplicitYear.equals(providedYear);
                boolean providedYearSeenInOcr = explicitYearFrequencies.containsKey(providedYear)
                        || fallbackYearFrequencies.containsKey(providedYear);

                if (explicitConflict) {
                    log.warn("Provided statement year {} conflicts with explicit OCR year {}. Using OCR year.",
                            providedYear, dominantExplicitYear);
                    return dominantExplicitYear;
                }
                if (!providedYearSeenInOcr && !detectedYear.equals(providedYear)) {
                    log.warn("Provided statement year {} not found in OCR. Using detected OCR year {}.",
                            providedYear, detectedYear);
                    return detectedYear;
                }
                if (Math.abs(detectedYear - providedYear) > 2) {
                    log.warn("Provided statement year {} conflicts with OCR year {}. Using OCR year.", providedYear,
                            detectedYear);
                    return detectedYear;
                }
            }
            return providedYear;
        }
        return detectedYear;
    }

    private Integer resolveStatementMonth(Integer providedMonth, String... texts) {
        if (providedMonth != null && providedMonth >= 1 && providedMonth <= 12) {
            return providedMonth;
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String[] lines = text.split("\n");
            for (String line : lines) {
                Matcher matcher = START_DAY_MONTH_PATTERN.matcher(line == null ? "" : line.trim());
                if (matcher.find()) {
                    int month = Integer.parseInt(matcher.group(2));
                    if (month >= 1 && month <= 12) {
                        return month;
                    }
                }
            }
        }
        return null;
    }

    private Integer parseYearToken(String token, Integer referenceYear) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(token.trim());
            if (parsed >= 100) {
                return (parsed >= 1900 && parsed <= 2100) ? parsed : null;
            }
            int y1900 = 1900 + parsed;
            int y2000 = 2000 + parsed;
            if (referenceYear != null && referenceYear >= 1900 && referenceYear <= 2100) {
                return Math.abs(referenceYear - y1900) <= Math.abs(referenceYear - y2000) ? y1900 : y2000;
            }
            return parsed <= 79 ? y2000 : y1900;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Détecte si le texte OCR a un layout à deux colonnes (Débit | Crédit séparées).
     *
     * Principe : dans un layout deux-colonnes, les montants en colonne Débit sont suivis
     * de nombreux espaces (la colonne Crédit est vide). Si l'OCR préserve ces espaces,
     * on trouve des lignes où un montant est suivi de 8+ caractères blancs.
     * Dans un layout une-colonne (Attijariwafa, BMCE…), les montants sont toujours
     * en fin de ligne sans espace de rembourrage.
     */
    private boolean detectTwoColumnAmountLayout(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int columnarLines = 0;
        for (String line : text.split("\n")) {
            Matcher m = AMOUNT_LAYOUT_PATTERN.matcher(line);
            while (m.find()) {
                if (m.end() < line.length()) {
                    String tail = line.substring(m.end());
                    if (tail.isBlank() && tail.length() >= TRAILING_COL_SPACE_MIN) {
                        columnarLines++;
                        break; // une seule occurrence suffisante par ligne
                    }
                }
            }
            if (columnarLines >= TWO_COLUMN_DETECTION_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private BankType mapManualType(String manualBankType) {
        if (manualBankType == null || manualBankType.isBlank()) {
            return null;
        }
        BankType resolved = BankAliasResolver.resolveType(manualBankType);
        if (resolved != null) {
            return resolved;
        }
        String normalized = manualBankType.trim().toUpperCase();
        for (BankType type : BankType.values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
