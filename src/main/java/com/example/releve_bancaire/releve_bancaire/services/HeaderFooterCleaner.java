package com.example.releve_bancaire.releve_bancaire.services;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class HeaderFooterCleaner {

    private static final List<Pattern> REMOVE_PATTERNS = List.of(
            Pattern.compile("CAPITAL\\s+SOCIAL", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bAU\\s+CAPITAL\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bICE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bRC\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ADRESSE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("TELEPHONE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTEL\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("PAGE\\s*\\d+\\s*/\\s*\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("TOTAL\\s+MOUVEMENTS", Pattern.CASE_INSENSITIVE),
            Pattern.compile("TOTAL\\s+DES\\s+MOUVEMENTS", Pattern.CASE_INSENSITIVE),
            Pattern.compile("SOLDE\\s+DEPART", Pattern.CASE_INSENSITIVE),
            Pattern.compile("SOLDE\\s+FINAL", Pattern.CASE_INSENSITIVE),
            Pattern.compile("REPORT", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CUMUL", Pattern.CASE_INSENSITIVE)
    );

    public String removeHeaderFooter(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String[] lines = text.split("\n");
        List<String> kept = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (shouldRemove(trimmed)) {
                continue;
            }

            kept.add(trimmed);
        }

        return String.join("\n", kept);
    }

    private boolean shouldRemove(String line) {
        for (Pattern pattern : REMOVE_PATTERNS) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
}
