package com.example.releve_bancaire.banking_services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Slf4j
public class BankDetector {

    private static final int HEADER_LINES = 40;
    private static final Pattern CODE_BANQUE_145_PATTERN = Pattern.compile("\\bCODE\\s+BANQUE\\b.{0,40}\\b145\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public BankDetection detect(String text) {
        if (text == null || text.isBlank()) {
            return new BankDetection(BankType.UNKNOWN, "");
        }

        List<String> lines = extractHeaderLines(text, HEADER_LINES);
        String header = String.join(" ", lines);
        String normalized = normalize(header);
        String normalizedAll = normalize(text);
        String compact = compactNormalize(header);
        String compactAll = compactNormalize(text);

        if (containsAny(normalized, "UMNIA", "UMNIA BANK")
                || containsAny(normalizedAll, "UMNIA", "UMNIA BANK")) {
            return new BankDetection(BankType.CIH, "UMNIA BANK");
        }
        if (containsAny(normalized, "BANK ASSAFA", "ASSAFA")
                || containsAny(normalizedAll, "BANK ASSAFA", "ASSAFA")) {
            return new BankDetection(BankType.ATTIJARIWAFA, "BANK ASSAFA");
        }
        if (containsAny(normalized, "BTI", "BTI BANK")
                || containsAny(normalizedAll, "BTI", "BTI BANK")) {
            return new BankDetection(BankType.BMCE, "BTI BANK");
        }
        if (containsAny(normalized, "BANK AL YOUSR", "AL YOUSR")
                || containsAny(normalizedAll, "BANK AL YOUSR", "AL YOUSR")) {
            return new BankDetection(BankType.BCP, "BANK AL YOUSR");
        }
        if (containsAny(normalized, "AL AKHDAR", "AL AKHDAR BANK")
                || containsAny(normalizedAll, "AL AKHDAR", "AL AKHDAR BANK")) {
            return new BankDetection(BankType.CREDIT_AGRICOLE, "AL AKHDAR BANK");
        }
        if (containsAny(normalized, "NAJMAH")
                || containsAny(normalizedAll, "NAJMAH")) {
            return new BankDetection(BankType.BMCI, "NAJMAH");
        }
        if (containsAny(normalized, "ARREDA")
                || containsAny(normalizedAll, "ARREDA")) {
            return new BankDetection(BankType.CREDIT_DU_MAROC, "ARREDA");
        }
        if (containsAny(normalized, "SAHAM", "SAHAM BANK")
                || containsAny(normalizedAll, "SAHAM", "SAHAM BANK")) {
            return new BankDetection(BankType.SAHAM_BANK, "SAHAM BANK");
        }
        if (containsAny(normalized, "DAR AL AMANE", "DAR AL-AMANE")
                || containsAny(normalizedAll, "DAR AL AMANE", "DAR AL-AMANE")) {
            return new BankDetection(BankType.SOCIETE_GENERALE, "DAR AL-AMANE");
        }

        if (containsAny(normalized, "BMCI")
                || containsAny(normalizedAll, "BMCI")) {
            return new BankDetection(BankType.BMCI, "BMCI");
        }
        if (containsAny(normalized, "BANK OF AFRICA", "BMCE")
                || containsAny(normalizedAll, "BANK OF AFRICA", "BMCE")) {
            return new BankDetection(BankType.BMCE, "BANK OF AFRICA (BMCE)");
        }
        if (containsAny(normalized, "ATTIJARIWAFA")
                || containsAny(normalizedAll, "ATTIJARIWAFA", "ATTIJARIWAFA BANK")) {
            return new BankDetection(BankType.ATTIJARIWAFA, "ATTIJARIWAFA");
        }
        if (containsAny(normalized, "SOCIETE GENERALE")
                || containsAny(normalizedAll, "SOCIETE GENERALE", "SG MAROC", "SGMB")) {
            return new BankDetection(BankType.SOCIETE_GENERALE, "SOCIETE GENERALE");
        }
        if (containsAny(normalized, "CREDIT DU MAROC")
                || containsAny(normalizedAll, "CREDIT DU MAROC")) {
            return new BankDetection(BankType.CREDIT_DU_MAROC, "CREDIT DU MAROC");
        }
        if (containsAny(normalized, "CIH")
                || containsAny(normalizedAll, "CIH", "CIH BANK")) {
            return new BankDetection(BankType.CIH, "CIH BANK");
        }
        if (containsAny(normalized, "CREDIT AGRICOLE")
                || containsAny(normalizedAll, "CREDIT AGRICOLE")) {
            return new BankDetection(BankType.CREDIT_AGRICOLE, "CREDIT AGRICOLE");
        }
        if (containsAny(normalized, "BARID BANK", "AL BARID")
                || containsAny(normalizedAll, "BARID BANK", "AL BARID")) {
            return new BankDetection(BankType.BARID_BANK, "AL BARID BANK");
        }
        if (containsAny(normalized, "BANQUE POPULAIRE", "BANQUE CENTRALE POPULAIRE", "BCP")
                || containsAny(normalizedAll, "BANQUE POPULAIRE", "BANQUE CENTRALE POPULAIRE", "BCP", "BANQE POPULAIRE")
                || containsAny(compact, "BANQUEPOPULAIRE", "BANQUECENTRALEPOPULAIRE", "GROUPEBANQUEPOPULAIRE", "BCP")
                || containsAny(compactAll, "BANQUEPOPULAIRE", "BANQUECENTRALEPOPULAIRE", "GROUPEBANQUEPOPULAIRE",
                        "BANQEPOPULAIRE", "BCP")
                || CODE_BANQUE_145_PATTERN.matcher(text).find()) {
            return new BankDetection(BankType.BCP, "BANQUE POPULAIRE");
        }

        return new BankDetection(BankType.UNKNOWN, "");
    }

    public BankType detectBankType(String text) {
        return detect(text).bankType;
    }

    private List<String> extractHeaderLines(String text, int maxLines) {
        String[] lines = text.split("\n");
        List<String> header = new ArrayList<>();
        for (int i = 0; i < lines.length && i < maxLines; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                header.add(line);
            }
        }
        return header;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        return normalized.toUpperCase();
    }

    private String compactNormalize(String input) {
        return normalize(input).replaceAll("[^A-Z0-9]+", "");
    }

    public static class BankDetection {
        public final BankType bankType;
        public final String bankName;

        public BankDetection(BankType bankType, String bankName) {
            this.bankType = bankType;
            this.bankName = bankName;
        }
    }
}
