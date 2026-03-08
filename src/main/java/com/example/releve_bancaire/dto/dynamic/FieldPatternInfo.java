package com.example.releve_bancaire.dto.dynamic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Informations agrégées sur un pattern
 * Utilisé pour l'analyse et les statistiques
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldPatternInfo {

    /**
     * Nom du champ
     */
    private String fieldName;

    /**
     * Pattern détecté
     */
    private String pattern;

    /**
     * Nombre d'occurrences
     */
    private Integer occurrenceCount;

    /**
     * Confiance moyenne
     */
    private Double averageConfidence;

    /**
     * Taux d'approbation (%)
     */
    private Double approvalRate;

    /**
     * Distance moyenne pattern-valeur
     */
    private Double averageDistance;

    /**
     * Zone la plus fréquente
     */
    private String mostFrequentZone;

    /**
     * Recommandé pour intégration ?
     */
    private Boolean recommendedForIntegration;

    /**
     * Raison de la recommandation
     */
    private String recommendationReason;
}
