package com.example.releve_bancaire.centremonetique.service;

import java.util.Locale;

public enum CentreMonetiqueStructureType {
    AUTO,
    CMI,
    BARID_BANK;

    public static CentreMonetiqueStructureType fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        String normalized = raw.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        if ("BARID".equals(normalized) || "AL_BARID_BANK".equals(normalized)) {
            return BARID_BANK;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return AUTO;
        }
    }
}
