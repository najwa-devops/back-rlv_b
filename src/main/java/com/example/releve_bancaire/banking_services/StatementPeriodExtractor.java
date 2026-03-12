package com.example.releve_bancaire.banking_services;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StatementPeriodExtractor {

    private static final Pattern PERIOD_PATTERN = Pattern.compile(
            "DU\\s+(\\d{1,2})\\s*[\\/\\-\\.\\s]+\\s*(\\d{1,2})\\s*[\\/\\-\\.\\s]+\\s*(\\d{2,4})\\s+AU\\s+"
                    + "(\\d{1,2})\\s*[\\/\\-\\.\\s]+\\s*(\\d{1,2})\\s*[\\/\\-\\.\\s]+\\s*(\\d{2,4})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TWO_DATES_HEADER_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\d+\\s*/\\s*\\d+\\s+)?"
                    + "(\\d{1,2})\\s*[\\/\\-\\.\\s]+\\s*(\\d{1,2})\\s*[\\/\\-\\.\\s]+\\s*(\\d{2,4})\\s+"
                    + "(\\d{1,2})\\s*[\\/\\-\\.\\s]+\\s*(\\d{1,2})\\s*[\\/\\-\\.\\s]+\\s*(\\d{2,4})\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_DATE_PATTERN = Pattern.compile(
            "(?:SOLDE\\s+FINAL|NOUVEAU\\s+SOLDE)\\s+AU\\s+(\\d{1,2})\\s*[\\/\\-\\.\\s]\\s*(\\d{1,2})\\s*[\\/\\-\\.\\s]\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE);
    // CIH Bank : "SOLDE DEPART AU : 31/12/2025" indique la date de début de période
    private static final Pattern SOLDE_DEPART_DATE_PATTERN = Pattern.compile(
            "SOLDE\\s+DEPART\\s+AU\\s*:?\\s*(\\d{1,2})\\s*[\\/\\-\\.\\s]\\s*(\\d{1,2})\\s*[\\/\\-\\.\\s]\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    public StatementPeriod extractPeriod(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized = normalizeAsciiUpper(text);

        StatementPeriod bestPeriod = null;
        int bestScore = Integer.MIN_VALUE;
        StatementPeriod bestNonContactPeriod = null;
        int bestNonContactScore = Integer.MIN_VALUE;
        EvaluatedPeriod evaluated = evaluateCandidates(normalized, PERIOD_PATTERN.matcher(normalized),
                bestPeriod, bestScore, bestNonContactPeriod, bestNonContactScore);
        bestPeriod = evaluated.bestPeriod;
        bestScore = evaluated.bestScore;
        bestNonContactPeriod = evaluated.bestNonContactPeriod;
        bestNonContactScore = evaluated.bestNonContactScore;

        evaluated = evaluateCandidates(normalized, TWO_DATES_HEADER_PATTERN.matcher(normalized),
                bestPeriod, bestScore, bestNonContactPeriod, bestNonContactScore);
        bestPeriod = evaluated.bestPeriod;
        bestScore = evaluated.bestScore;
        bestNonContactPeriod = evaluated.bestNonContactPeriod;
        bestNonContactScore = evaluated.bestNonContactScore;
        if (bestNonContactPeriod != null) {
            return bestNonContactPeriod;
        }
        if (bestPeriod != null) {
            return bestPeriod;
        }

        Matcher closingMatcher = CLOSING_DATE_PATTERN.matcher(normalized);
        if (closingMatcher.find()) {
            LocalDate end = parseDate(closingMatcher.group(1), closingMatcher.group(2), closingMatcher.group(3));
            if (end != null) {
                LocalDate start = null;
                Matcher departMatcher = SOLDE_DEPART_DATE_PATTERN.matcher(normalized);
                if (departMatcher.find()) {
                    start = parseDate(departMatcher.group(1), departMatcher.group(2), departMatcher.group(3));
                }
                return new StatementPeriod(start, end, end.getMonthValue(), end.getYear());
            }
        }

        return null;
    }

    private EvaluatedPeriod evaluateCandidates(String normalized, Matcher matcher,
                                               StatementPeriod bestPeriod, int bestScore,
                                               StatementPeriod bestNonContactPeriod, int bestNonContactScore) {
        StatementPeriod currentBest = bestPeriod;
        int currentBestScore = bestScore;
        StatementPeriod currentBestNonContact = bestNonContactPeriod;
        int currentBestNonContactScore = bestNonContactScore;

        while (matcher.find()) {
            LocalDate start = parseDate(matcher.group(1), matcher.group(2), matcher.group(3));
            LocalDate end = parseDate(matcher.group(4), matcher.group(5), matcher.group(6));
            if (end == null) {
                continue;
            }
            int score = scoreCandidate(normalized, matcher.start(), matcher.end(), start, end);
            boolean contactRelated = isContactRelated(normalized, matcher.start(), matcher.end());

            if (score > currentBestScore) {
                currentBestScore = score;
                currentBest = new StatementPeriod(start, end, end.getMonthValue(), end.getYear());
            }
            if (!contactRelated && score > currentBestNonContactScore) {
                currentBestNonContactScore = score;
                currentBestNonContact = new StatementPeriod(start, end, end.getMonthValue(), end.getYear());
            }
        }
        return new EvaluatedPeriod(currentBest, currentBestScore, currentBestNonContact, currentBestNonContactScore);
    }

    private static class EvaluatedPeriod {
        private final StatementPeriod bestPeriod;
        private final int bestScore;
        private final StatementPeriod bestNonContactPeriod;
        private final int bestNonContactScore;

        private EvaluatedPeriod(StatementPeriod bestPeriod, int bestScore,
                                StatementPeriod bestNonContactPeriod, int bestNonContactScore) {
            this.bestPeriod = bestPeriod;
            this.bestScore = bestScore;
            this.bestNonContactPeriod = bestNonContactPeriod;
            this.bestNonContactScore = bestNonContactScore;
        }
    }

    private LocalDate parseDate(String d, String m, String y) {
        try {
            int day = Integer.parseInt(d);
            int month = Integer.parseInt(m);
            int year = parseYearToken(y);
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseYearToken(String token) {
        int parsed = Integer.parseInt(token);
        if (parsed >= 100) {
            return parsed;
        }
        return parsed <= 79 ? 2000 + parsed : 1900 + parsed;
    }

    private int scoreCandidate(String normalizedText, int startIndex, int endIndex, LocalDate startDate, LocalDate endDate) {
        int score = 0;
        int contextStart = Math.max(0, startIndex - 120);
        int contextEnd = Math.min(normalizedText.length(), endIndex + 120);
        String context = normalizedText.substring(contextStart, contextEnd);
        String immediatePrefix = normalizedText.substring(Math.max(0, startIndex - 32), startIndex);

        if (context.contains("COMPTE DE PARTICULIER")) {
            score += 12;
        }
        if (context.contains("RELEVE DE COMPTE") || context.contains("RELEVE")) {
            score += 6;
        }
        if (context.contains("VOS CONTACTS") || immediatePrefix.contains("VOS CONTACTS")) {
            score -= 25;
        }

        if (startDate != null && !endDate.isBefore(startDate)) {
            long days = ChronoUnit.DAYS.between(startDate, endDate);
            if (days >= 20 && days <= 45) {
                score += 4;
            } else if (days <= 90) {
                score += 2;
            } else {
                score -= 2;
            }
        }
        return score;
    }

    private boolean isContactRelated(String normalizedText, int startIndex, int endIndex) {
        int contextStart = Math.max(0, startIndex - 40);
        int contextEnd = Math.min(normalizedText.length(), endIndex + 40);
        String context = normalizedText.substring(contextStart, contextEnd);
        return context.contains("VOS CONTACTS");
    }

    private String normalizeAsciiUpper(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase();
    }

    public static class StatementPeriod {
        public final LocalDate startDate;
        public final LocalDate endDate;
        public final Integer month;
        public final Integer year;

        public StatementPeriod(LocalDate startDate, LocalDate endDate, Integer month, Integer year) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.month = month;
            this.year = year;
        }

        public String formatPeriod() {
            if (month == null || year == null) {
                return "";
            }
            return String.format("%02d/%04d", month, year);
        }
    }
}
