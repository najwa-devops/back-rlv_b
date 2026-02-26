package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.banking_services.banking_ocr.OcrCleaningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class PrimaryRibExtractor {

    private final OcrCleaningService cleaningService;

    private static final int HEADER_LINES = 120;
    private static final Pattern DATE_START_PATTERN = Pattern.compile("^\\s*\\d{1,2}\\s*[\\/\\-\\.\\s]\\s*\\d{1,2}");
    private static final Pattern[] RIB_PATTERNS = {
            Pattern.compile("\\b(\\d{5})\\s+(\\d{5})\\s+(\\d{11})\\s+(\\d{2})\\b"),
            Pattern.compile("\\b(\\d{3})\\s+(\\d{3})\\s+(\\d{2})\\s+(\\d{14})\\s+(\\d{2})\\b"),
            Pattern.compile("\\b(\\d{3})\\s+(\\d{3})\\s+(\\d{16})\\s+(\\d{2})\\b"),
            Pattern.compile("\\b(\\d{23})\\b"),
            Pattern.compile("\\b(\\d{24})\\b")
    };

    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "VIREMENT",
            "DE:",
            "BENEFICIAIRE",
            "POUR",
            "REFERENCE",
            "MOTIF"
    );

    public String extractPrimaryRib(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        List<String> lines = extractHeaderLines(text, HEADER_LINES);
        for (String line : lines) {
            if (DATE_START_PATTERN.matcher(line).find()) {
                // On continue: certains relevés affichent le RIB après les premières dates.
            }

            String rib = extractRibFromLine(line);
            if (rib != null) {
                return rib;
            }
        }

        return cleaningService.extractRib(text);
    }

    private String extractRibFromLine(String line) {
        String upper = line.toUpperCase();

        for (Pattern pattern : RIB_PATTERNS) {
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                String prefix = line.substring(0, matcher.start()).toUpperCase();
                if (isForbiddenPrefix(prefix) || isForbiddenPrefix(upper)) {
                    continue;
                }

                String rib = buildRibFromGroups(matcher);
                if (rib.length() == 23 || rib.length() == 24) {
                    return rib;
                }
            }
        }

        String digitsOnly = line.replaceAll("\\D", "");
        if (!isForbiddenPrefix(upper) && (digitsOnly.length() == 23 || digitsOnly.length() == 24)) {
            return digitsOnly;
        }

        return null;
    }

    private String buildRibFromGroups(Matcher matcher) {
        StringBuilder rib = new StringBuilder();
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String group = matcher.group(i);
            if (group != null) {
                rib.append(group);
            }
        }
        return rib.toString();
    }

    private boolean isForbiddenPrefix(String prefix) {
        for (String forbidden : FORBIDDEN_PREFIXES) {
            if (prefix.contains(forbidden)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractHeaderLines(String text, int maxLines) {
        String[] rawLines = text.split("\n");
        List<String> header = new ArrayList<>();
        for (int i = 0; i < rawLines.length && i < maxLines; i++) {
            String line = rawLines[i].trim();
            if (!line.isEmpty()) {
                header.add(line);
            }
        }
        return header;
    }
}
