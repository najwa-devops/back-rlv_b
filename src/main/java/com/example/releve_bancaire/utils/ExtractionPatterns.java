package com.example.releve_bancaire.utils;

/**
 * Classe utilitaire contenant les patterns d'extraction pour les identifiants
 * fiscaux marocains
 * IF (Identifiant Fiscal), ICE (Identifiant Commun de l'Entreprise), RC
 * (Registre de Commerce)
 */
public class ExtractionPatterns {

    /**
     * Patterns pour extraire l'ICE (15 chiffres)
     * Format: 000123456789012
     */
    public static final String[] ICE_PATTERNS = {
            // Pattern 1: "ICE : 000123456789012"
            "(?i)ICE\\s*[:.]?\\s*(\\d{15})",

            // Pattern 2: "I.C.E. : 000123456789012"
            "(?i)I\\.\\s*C\\.\\s*E\\.?\\s*[:.]?\\s*(\\d{15})",

            // Pattern 3: "I C E : 000123456789012"
            "(?i)I\\s+C\\s+E\\s*[:.]?\\s*(\\d{15})",

            // Pattern 4: "N° ICE: 003153509000014"
            "(?i)N°\\s*ICE\\s*[:.]?\\s*(\\d{15})",

            // Pattern 4b: "ICE : 000079569 000090" (espaces/points OCR)
            "(?i)ICE\\s*[:.]?\\s*((?:\\d[\\s\\.]*){15})",

            // Pattern 5: Fallback - 15 chiffres seuls
            "\\b(\\d{15})\\b"
    };

    /**
     * Patterns pour extraire l'IF (Identifiant Fiscal - 7 à 10 chiffres)
     * Format: 1234567 ou 12345678
     */
    public static final String[] IF_PATTERNS = {
            // Pattern 1: "I.F. : 1234567" (OCR peut confondre I/L)
            "(?i)[IL]\\.?\\s*F\\.?\\s*[:.]?\\s*(\\d{7,10})",

            // Pattern 2: "Identifiant Fiscal : 1234567"
            "(?i)Identifiant\\s+Fiscal\\s*[:.]?\\s*(\\d{7,10})",

            // Pattern 3: "IF : 1234567"
            "(?i)IF\\s*[:.]?\\s*(\\d{7,10})",

            // Pattern 4: "IF N°:52675350"
            "(?i)IF\\s*N°\\s*[:.]?\\s*(\\d{7,10})",

            // Pattern 5: "I F : 1234567"
            "(?i)[IL]\\s+F\\s*[:.]?\\s*(\\d{7,10})",

            // Pattern 6: OCR confusion "LF : 1234567"
            "(?i)L\\.?\\s*F\\.?\\s*[:.]?\\s*(\\d{7,10})"
    };

    /**
     * Patterns pour extraire le RC (Registre de Commerce)
     * Format variable selon les villes
     */
    public static final String[] RC_PATTERNS = {
            // Pattern 1: "RC : 12345"
            "(?i)RC\\s*[:.]?\\s*(\\d{4,10})",

            // Pattern 2: "R.C. : 12345"
            "(?i)R\\.\\s*C\\.\\s*[:.]?\\s*(\\d{4,10})",

            // Pattern 3: "Registre de Commerce : 12345"
            "(?i)Registre\\s+de\\s+Commerce\\s*[:.]?\\s*(\\d{4,10})",

            // Pattern 4: "N° RC: 129189"
            "(?i)N°\\s*RC\\s*[:.]?\\s*(\\d{4,10})",

            // Pattern 5: "R C : 12345"
            "(?i)R\\s+C\\s*[:.]?\\s*(\\d{4,10})",

            // Pattern 6: OCR with separators/noise "R.?C.?: 12345"
            "(?i)R\\.?\\s*\\??\\s*C\\.?\\s*[:.]?\\s*(\\d{4,10})"
    };

    /**
     * Nettoie un numéro en supprimant les espaces
     * 
     * @param number Le numéro à nettoyer
     * @return Le numéro nettoyé
     */
    public static String cleanNumber(String number) {
        if (number == null) {
            return null;
        }
        return number.replaceAll("\\s+", "");
    }

    /**
     * Valide un ICE (doit contenir exactement 15 chiffres)
     * 
     * @param ice L'ICE à valider
     * @return true si valide, false sinon
     */
    public static boolean isValidICE(String ice) {
        if (ice == null) {
            return false;
        }
        String cleaned = cleanNumber(ice);
        return cleaned.matches("\\d{15}");
    }

    /**
     * Valide un IF (doit contenir entre 7 et 10 chiffres)
     * 
     * @param ifNumber L'IF à valider
     * @return true si valide, false sinon
     */
    public static boolean isValidIF(String ifNumber) {
        if (ifNumber == null) {
            return false;
        }
        String cleaned = cleanNumber(ifNumber);
        return cleaned.matches("\\d{7,10}");
    }

    /**
     * Valide un RC (doit contenir entre 4 et 10 chiffres)
     * 
     * @param rc Le RC à valider
     * @return true si valide, false sinon
     */
    public static boolean isValidRC(String rc) {
        if (rc == null) {
            return false;
        }
        String cleaned = cleanNumber(rc);
        return cleaned.matches("\\d{4,10}");
    }
}
