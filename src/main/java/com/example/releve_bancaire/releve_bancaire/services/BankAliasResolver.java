package com.example.releve_bancaire.releve_bancaire.services;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BankAliasResolver {

    private static final Map<String, BankType> TOKEN_TO_TYPE = Map.ofEntries(
            Map.entry("BCP", BankType.BCP),
            Map.entry("BANQUE POPULAIRE", BankType.BCP),
            Map.entry("BANK AL YOUSR", BankType.BCP),
            Map.entry("AL YOUSR", BankType.BCP),

            Map.entry("CREDIT DU MAROC", BankType.CREDIT_DU_MAROC),
            Map.entry("ARREDA", BankType.CREDIT_DU_MAROC),

            Map.entry("BMCI", BankType.BMCI),
            Map.entry("NAJMAH", BankType.BMCI),

            Map.entry("CIH", BankType.CIH),
            Map.entry("CIH BANK", BankType.CIH),
            Map.entry("UMNIA", BankType.CIH),
            Map.entry("UMNIA BANK", BankType.CIH),

            Map.entry("CREDIT AGRICOLE", BankType.CREDIT_AGRICOLE),
            Map.entry("AL AKHDAR BANK", BankType.CREDIT_AGRICOLE),
            Map.entry("AL AKHDAR", BankType.CREDIT_AGRICOLE),

            Map.entry("SOCIETE GENERALE", BankType.SOCIETE_GENERALE),
            Map.entry("SG MAROC", BankType.SOCIETE_GENERALE),
            Map.entry("SGMB", BankType.SOCIETE_GENERALE),
            Map.entry("SAHAM BANK", BankType.SOCIETE_GENERALE),
            Map.entry("SAHAM", BankType.SOCIETE_GENERALE),
            Map.entry("DAR AL AMANE", BankType.SOCIETE_GENERALE),
            Map.entry("DAR AL-AMANE", BankType.SOCIETE_GENERALE),

            Map.entry("AL BARID", BankType.BARID_BANK),
            Map.entry("BARID BANK", BankType.BARID_BANK),
            Map.entry("AL BARID BANK", BankType.BARID_BANK),

            Map.entry("BMCE", BankType.BMCE),
            Map.entry("BANK OF AFRICA", BankType.BMCE),
            Map.entry("BTI", BankType.BMCE),
            Map.entry("BTI BANK", BankType.BMCE),

            Map.entry("ATTIJARIWAFA", BankType.ATTIJARIWAFA),
            Map.entry("ATTIJARIWAFA BANK", BankType.ATTIJARIWAFA),
            Map.entry("AWB", BankType.ATTIJARIWAFA),
            Map.entry("BANK ASSAFA", BankType.ATTIJARIWAFA),
            Map.entry("ASSAFA", BankType.ATTIJARIWAFA));

    private BankAliasResolver() {
    }

    public static BankType resolveType(String token) {
        String normalized = normalizeToken(token);
        if (normalized.isEmpty()) {
            return null;
        }
        BankType direct = TOKEN_TO_TYPE.get(normalized);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, BankType> entry : TOKEN_TO_TYPE.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static String normalizeAllowedBankCode(String token) {
        String normalized = normalizeToken(token);
        if (normalized.isEmpty()) {
            return "";
        }
        if (isAutoToken(normalized)) {
            return "AUTO";
        }

        BankType resolved = resolveType(token);
        if (resolved != null) {
            return resolved.name();
        }
        return normalized.replace(' ', '_');
    }

    public static List<Map<String, String>> bankChoices() {
        return List.of(
                choice("AUTO", "Détection automatique", "AUTO"),
                choice(BankType.ATTIJARIWAFA.name(), "Attijariwafa", BankType.ATTIJARIWAFA.name()),
                choice(BankType.BCP.name(), "Banque Populaire (BCP)", BankType.BCP.name()),
                choice(BankType.BMCE.name(), "Bank of Africa (BMCE)", BankType.BMCE.name()),
                choice(BankType.CIH.name(), "CIH Bank", BankType.CIH.name()),
                choice(BankType.CREDIT_DU_MAROC.name(), "Crédit du Maroc", BankType.CREDIT_DU_MAROC.name()),
                choice(BankType.BMCI.name(), "BMCI", BankType.BMCI.name()),
                choice(BankType.SOCIETE_GENERALE.name(), "Société Générale", BankType.SOCIETE_GENERALE.name()),
                choice(BankType.CREDIT_AGRICOLE.name(), "Crédit Agricole", BankType.CREDIT_AGRICOLE.name()),
                choice(BankType.BARID_BANK.name(), "Al Barid Bank", BankType.BARID_BANK.name()),

                choice("UMNIA_BANK", "Umnia Bank (participative)", BankType.CIH.name()),
                choice("BANK_ASSAFA", "Bank Assafa (participative)", BankType.ATTIJARIWAFA.name()),
                choice("BTI_BANK", "BTI Bank (participative)", BankType.BMCE.name()),
                choice("BANK_AL_YOUSR", "Bank Al Yousr (participative)", BankType.BCP.name()),
                choice("AL_AKHDAR_BANK", "Al Akhdar Bank (participative)", BankType.CREDIT_AGRICOLE.name()),
                choice("NAJMAH", "Najmah (participative)", BankType.BMCI.name()),
                choice("ARREDA", "Arreda (participative)", BankType.CREDIT_DU_MAROC.name()),
                choice("SAHAM_BANK", "Saham Bank", BankType.SOCIETE_GENERALE.name()),
                choice("DAR_AL_AMANE", "Dar Al-Amane (participative)", BankType.SOCIETE_GENERALE.name()));
    }

    private static Map<String, String> choice(String code, String label, String mappedTo) {
        return Map.of(
                "code", code,
                "label", label,
                "mappedTo", mappedTo);
    }

    private static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        normalized = normalized.toUpperCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^A-Z0-9]+", " ").trim().replaceAll("\\s{2,}", " ");
        return normalized;
    }

    private static boolean isAutoToken(String normalizedToken) {
        return "AUTO".equals(normalizedToken)
                || "AUTOMATIQUE".equals(normalizedToken)
                || "AUTOMATIC".equals(normalizedToken)
                || "DETECTION AUTO".equals(normalizedToken)
                || "DETECTION AUTOMATIQUE".equals(normalizedToken)
                || "DETECTION AUTOMATIC".equals(normalizedToken)
                || (normalizedToken.contains("DETECTION") && normalizedToken.contains("AUTO"));
    }
}
