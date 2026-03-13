package com.example.releve_bancaire.releve_bancaire.entity;

/**
 * État de la continuité mensuelle d'un relevé
 */
public enum ContinuityStatus {
    FIRST_STATEMENT, // Premier relevé pour ce compte
    CONSISTENT, // Solde d'ouverture correspond au solde de clôture précédent
    INCONSISTENT_BALANCE, // Écart de solde détecté
    MISSING_PREVIOUS // Mois précédent manquant dans la base
}
