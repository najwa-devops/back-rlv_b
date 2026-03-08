package com.example.releve_bancaire.utils;

import java.util.Locale;
import java.util.Map;

public final class InvoiceTypeDetector {

    private InvoiceTypeDetector() {}

    public static boolean isAvoir(Map<String, Object> fieldsData, String rawOcrText) {
        if (fieldsData == null || fieldsData.isEmpty()) {
            return false;
        }

        if (containsAvoirKeyword(rawOcrText)) {
            return true;
        }

        Object amountHT = fieldsData.get("amountHT");
        Object amountTTC = fieldsData.get("amountTTC");
        if (isNegativeAmount(amountHT) || isNegativeAmount(amountTTC)) {
            return true;
        }

        Double tva = parseAmount(fieldsData.get("tva"));
        Double ht = parseAmount(amountHT);
        Double ttc = parseAmount(amountTTC);
        if (tva != null && Math.abs(tva) <= 0.01 && ht != null && ttc != null) {
            if (Math.abs(ht - ttc) <= 0.01) {
                return true;
            }
        }

        return false;
    }

    public static boolean isAvoir(Map<String, Object> fieldsData) {
        return isAvoir(fieldsData, null);
    }

    private static boolean containsAvoirKeyword(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).toLowerCase(Locale.ROOT);
        return text.contains("avoir") || text.contains("credit");
    }

    private static boolean isNegativeAmount(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number n) {
            return n.doubleValue() < 0;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return false;
        }
        if (raw.startsWith("-") || raw.contains("(-") || raw.startsWith("(")) {
            return true;
        }
        Double parsed = parseAmount(raw);
        return parsed != null && parsed < 0;
    }

    private static Double parseAmount(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }

        String cleaned = raw
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9,\\.\\-]", "");

        if (cleaned.isBlank() || "-".equals(cleaned)) {
            return null;
        }

        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                cleaned = cleaned.replace(".", "");
                cleaned = cleaned.replace(',', '.');
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (lastComma >= 0) {
            cleaned = cleaned.replace(',', '.');
        }

        int firstDot = cleaned.indexOf('.');
        while (firstDot != -1 && firstDot != cleaned.lastIndexOf('.')) {
            cleaned = cleaned.substring(0, firstDot) + cleaned.substring(firstDot + 1);
            firstDot = cleaned.indexOf('.');
        }

        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
