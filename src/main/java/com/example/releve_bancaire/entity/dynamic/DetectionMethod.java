package com.example.releve_bancaire.entity.dynamic;

/**
 * Méthodes de détection des champs dans le texte OCR
 */
public enum DetectionMethod {
    LABEL_BASED, // Recherche par label (ex: "Total TTC" → valeur suivante)
    REGEX_BASED, // Recherche par expression régulière uniquement
    HYBRID, // Combinaison label + regex (plus robuste)
    CONTEXTUAL // Détection par contexte/proximité logique
}
