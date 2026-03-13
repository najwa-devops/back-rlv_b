package com.example.releve_bancaire.releve_bancaire.services;

import com.example.releve_bancaire.releve_bancaire.services.banking_ocr.OcrCleaningService;
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
    // Patterns structurés (séparés par espaces) : fiables sans mot-clé
    private static final Pattern[] STRUCTURED_RIB_PATTERNS = {
            Pattern.compile("\\b(\\d{5})\\s+(\\d{5})\\s+(\\d{11})\\s+(\\d{2})\\b"),
            Pattern.compile("\\b(\\d{3})\\s+(\\d{3})\\s+(\\d{2})\\s+(\\d{14})\\s+(\\d{2})\\b"),
            Pattern.compile("\\b(\\d{3})\\s+(\\d{3})\\s+(\\d{16})\\s+(\\d{2})\\b"),
    };
    // Patterns larges (séquence continue) : nécessitent un mot-clé RIB sur la ligne
    private static final Pattern[] BROAD_RIB_PATTERNS = {
            Pattern.compile("\\b(\\d{23})\\b"),
            Pattern.compile("\\b(\\d{24})\\b")
    };
    private static final List<String> RIB_KEYWORDS = List.of(
            "RIB", "COMPTE", "N° DE COMPTE", "NUMERO DE COMPTE", "IBAN", "TITULAIRE"
    );

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
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            String rib = extractRibFromLine(line);
            if (rib != null) {
                return rib;
            }

            // Multi-lignes : le mot-clé RIB peut être AVANT ou APRÈS le numéro RIB
            // SAHAM BANK : "022 450 000 172 00 050693 28 53" \n "Banque C.Ville Clé Rib"
            //              → keyword sur la ligne SUIVANTE, RIB sur la ligne courante (déjà testé)
            //              → si on arrive sur la ligne keyword, chercher en arrière aussi
            if (hasRibKeyword(line.toUpperCase())) {
                // Forward : RIB sur les 3 lignes suivantes
                for (int j = i + 1; j < Math.min(i + 4, lines.size()); j++) {
                    rib = extractRibAllPatterns(lines.get(j));
                    if (rib != null) {
                        return rib;
                    }
                }
                // Backward : RIB sur les 3 lignes précédentes (SAHAM BANK)
                for (int j = i - 1; j >= Math.max(0, i - 3); j--) {
                    rib = extractRibAllPatterns(lines.get(j));
                    if (rib != null) {
                        return rib;
                    }
                }
            }
        }

        return cleaningService.extractRib(text);
    }

    private String extractRibFromLine(String line) {
        String upper = line.toUpperCase();

        for (Pattern pattern : STRUCTURED_RIB_PATTERNS) {
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

        if (hasRibKeyword(upper)) {
            for (Pattern pattern : BROAD_RIB_PATTERNS) {
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
        }

        return null;
    }

    /**
     * Tente tous les patterns sur une ligne — utilisé uniquement quand le contexte RIB est confirmé
     * (ligne adjacente contenant un mot-clé "RIB", "COMPTE", etc.).
     * Inclut un fallback digitsOnly sûr dans ce contexte.
     */
    private String extractRibAllPatterns(String line) {
        String upper = line.toUpperCase();
        if (isForbiddenPrefix(upper)) {
            return null;
        }
        for (Pattern pattern : STRUCTURED_RIB_PATTERNS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String rib = buildRibFromGroups(matcher);
                if (rib.length() == 23 || rib.length() == 24) {
                    return rib;
                }
            }
        }
        for (Pattern pattern : BROAD_RIB_PATTERNS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String rib = buildRibFromGroups(matcher);
                if (rib.length() == 23 || rib.length() == 24) {
                    return rib;
                }
            }
        }
        // SAHAM BANK : "022 450 000 172 00 050693 28 53" → aucun pattern ne matche car
        // le numéro de compte est subdivisé en trop de groupes.
        // Sûr ici car on est en contexte RIB confirmé.
        String digitsOnly = line.replaceAll("\\D", "");
        if (digitsOnly.length() == 23 || digitsOnly.length() == 24) {
            return digitsOnly;
        }
        return null;
    }

    private boolean hasRibKeyword(String upperLine) {
        for (String keyword : RIB_KEYWORDS) {
            if (upperLine.contains(keyword)) {
                return true;
            }
        }
        return false;
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
