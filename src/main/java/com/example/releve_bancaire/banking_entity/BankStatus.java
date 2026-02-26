package com.example.releve_bancaire.banking_entity;

public enum BankStatus {

    PENDING,
    PROCESSING,
    TREATED,
    READY_TO_VALIDATE,
    VALIDATED,
    COMPTABILISE,
    ERROR,
    VIDE,
    DUPLIQUE;

    public static BankStatus fromExternalValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim().toUpperCase();

        return switch (normalized) {
            case "PENDING", "EN_ATTENTE" -> PENDING;
            case "PROCESSING", "EN_COURS" -> PROCESSING;
            case "TREATED", "TRAITE" -> TREATED;
            case "READY_TO_VALIDATE", "PRET_A_VALIDER" -> READY_TO_VALIDATE;
            case "VALIDATED", "VALIDE" -> VALIDATED;
            case "COMPTABILISE", "ACCOUNTED" -> COMPTABILISE;
            case "ERROR", "ERREUR" -> ERROR;
            case "VIDE" -> VIDE;
            case "DUPLIQUE", "DUPLICATE" -> DUPLIQUE;
            default -> {
                try {
                    yield BankStatus.valueOf(normalized);
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
            }
        };
    }
}
