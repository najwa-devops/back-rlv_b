package com.example.releve_bancaire.dto.dynamic;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaveFieldsWithPatternsRequest {

    /**
     * Valeurs des champs
     * Exemple: { "invoiceNumber": "FAC-2026-001", "supplier": "EVOLEO SARL" }
     */
    @NotNull(message = "fieldsData est requis")
    private Map<String, Object> fieldsData;

    /**
     * Patterns détectés par l'utilisateur
     * Exemple: { "invoiceNumber": "N° Facture", "supplier": "Fournisseur" }
     */
    private Map<String, String> fieldPatterns;

    /**
     * Positions des patterns dans le document (optionnel)
     * Exemple: { "invoiceNumber": { "x": 10.5, "y": 15.2, "width": 20.0, "height":
     * 3.5 } }
     */
    private Map<String, Map<String, Double>> patternPositions;

    /**
     * Positions des valeurs dans le document (optionnel)
     */
    private Map<String, Map<String, Double>> valuePositions;

    /**
     * Zones des champs (HEADER, BODY, FOOTER)
     * Exemple: { "invoiceNumber": "HEADER", "amountTTC": "FOOTER" }
     */
    private Map<String, String> fieldZones;

    /**
     * Contexte OCR autour de chaque pattern (optionnel)
     * Exemple: { "invoiceNumber": "...Document N° Facture: FAC-2026-001 Date..." }
     */
    private Map<String, String> ocrContexts;

    /**
     * Confiance de l'utilisateur pour chaque champ (0.0 à 1.0)
     * Plus élevée si l'utilisateur n'a pas hésité
     */
    private Map<String, Double> confidenceScores;

    /**
     * Utilisateur qui a fait la correction/sélection
     */
    private String userId;

    /**
     * Méthode de détection: USER_SELECTION, AUTO_CORRECTION, ML_SUGGESTION
     */
    private String detectionMethod = "USER_SELECTION";

    private Map<String, String> newPatterns;
}
