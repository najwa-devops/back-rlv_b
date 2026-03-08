package com.example.releve_bancaire.dto.account_tier;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO pour la création d'un tier - MODE AUXILIAIRE
 *
 * LOGIQUE FRONTEND:
 *
 * SI auxiliaireMode = true:
 * Frontend affiche:
 * - Dropdown: Compte collectif (342100000 ou 441100000)
 * - Input: Numéro tier (9 chiffres)
 *
 * SI auxiliaireMode = false:
 * Frontend affiche:
 * - Input: Numéro tier uniquement (9 chiffres)
 *
 * VALIDATION:
 * - tierNumber: TOUJOURS 9 chiffres
 * - collectifAccount: 9 chiffres (si mode auxiliaire)
 * - defaultChargeAccount: 9 chiffres
 * - tvaAccount: 9 chiffres
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateTierRequest {

    // ===================== MODE AUXILIAIRE =====================

    /**
     * Mode auxiliaire activé/désactivé
     * OBLIGATOIRE
     */
    @NotNull(message = "Le mode auxiliaire doit être spécifié (true ou false)")
    private Boolean auxiliaireMode;

    // ===================== COMPTES =====================

    /**
     * Numéro de tier
     * Format: EXACTEMENT 9 chiffres
     * OBLIGATOIRE (tous les modes)
     *
     * Exemples valides:
     * - "000000123"
     * - "000001234"
     * - "441100123"
     */
    @Size(max = 31, message = "Le numéro de tier ne doit pas dépasser 20 caractères")
    @Pattern(regexp = "^[A-Z0-9\\-]+$|^$", message = "Le numéro de tier ne peut contenir que des lettres majuscules, chiffres et tirets")
    private String tierNumber;

    /**
     * Compte collectif (mode auxiliaire uniquement)
     * Format: EXACTEMENT 9 chiffres
     * Valeurs autorisées: "342100000" ou "441100000"
     *
     * OBLIGATOIRE si auxiliaireMode = true
     * NON UTILISÉ si auxiliaireMode = false
     */
    @Pattern(regexp = "^(342100000|441100000)$|^$", message = "Le compte collectif doit être '342100000' ou '441100000'")
    private String collectifAccount;

    // ===================== IDENTIFICATION =====================

    /**
     * Libellé du fournisseur
     * OBLIGATOIRE
     */
    @NotBlank(message = "Le libellé est obligatoire")
    @Size(min = 2, max = 200, message = "Le libellé doit contenir entre 2 et 200 caractères")
    private String libelle;

    // ===================== IDENTIFIANTS FISCAUX =====================

    /**
     * IF - 7 à 10 chiffres
     * OPTIONNEL
     */
    @Pattern(regexp = "^\\d{7,10}$|^$", message = "L'IF doit contenir entre 7 et 10 chiffres")
    private String ifNumber;

    /**
     * ICE - 15 chiffres
     * OPTIONNEL
     */
    @Pattern(regexp = "^\\d{15}$|^$", message = "L'ICE doit contenir exactement 15 chiffres")
    private String ice;

    /**
     * RC
     * OPTIONNEL
     */
    @Size(max = 20, message = "Le numéro RC ne peut pas dépasser 20 caractères")
    private String rcNumber;

    // ===================== CONFIGURATION COMPTABLE =====================

    /**
     * Compte de charge par défaut
     * Format: EXACTEMENT 9 chiffres
     * OPTIONNEL
     */
    @Pattern(regexp = "^\\d{9}$|^$", message = "Le compte de charge doit contenir exactement 9 chiffres")
    private String defaultChargeAccount;

    /**
     * Compte TVA
     * Format: EXACTEMENT 9 chiffres
     * OPTIONNEL
     */
    @Pattern(regexp = "^\\d{9}$|^$", message = "Le compte TVA doit contenir exactement 9 chiffres")
    private String tvaAccount;

    /**
     * Taux de TVA par défaut
     * OPTIONNEL
     */
    @Min(value = 0, message = "Le taux de TVA doit être positif")
    @Max(value = 100, message = "Le taux de TVA ne peut pas dépasser 100%")
    private Double defaultTvaRate;

    // ===================== STATUT =====================

    /**
     * Statut actif (true par défaut)
     */
    private Boolean active;

    /**
     * Utilisateur créateur
     */
    private String createdBy;

    // ===================== VALIDATION MÉTIER =====================

    /**
     * Valide la cohérence des données selon le mode auxiliaire
     */
    public void validate() {
        // Validation mode auxiliaire
        if (Boolean.TRUE.equals(auxiliaireMode)) {
            if (tierNumber == null || tierNumber.isBlank()) {
                throw new IllegalArgumentException("Le numéro de tier est requis en mode auxiliaire");
            }
            if (collectifAccount == null || collectifAccount.isBlank()) {
                throw new IllegalArgumentException("Le compte collectif est requis en mode auxiliaire");
            }
        } else {
            if (collectifAccount != null && !collectifAccount.isBlank()) {
                throw new IllegalArgumentException("Le compte collectif n'est utilisé qu'en mode auxiliaire");
            }
        }

        // Validation identifiants fiscaux
        if ((ifNumber == null || ifNumber.isBlank()) &&
                (ice == null || ice.isBlank()) &&
                (rcNumber == null || rcNumber.isBlank())) {
            throw new IllegalArgumentException(
                    "Au moins un identifiant fiscal est requis (IF, ICE, ou RC)");
        }

        validateTvaConfiguration();
    }

    private void validateTvaConfiguration() {
        boolean hasTvaAccount = tvaAccount != null && !tvaAccount.isBlank();
        boolean hasTvaRate = defaultTvaRate != null;

        // CAS 1: Compte fourni SANS taux → ERREUR
        if (hasTvaAccount && !hasTvaRate) {
            throw new IllegalArgumentException(
                    "Le taux de TVA est OBLIGATOIRE quand un compte TVA est fourni. " +
                            "Exemple: tvaAccount='34552' + defaultTvaRate=20.0");
        }

        // CAS 2: Taux fourni SANS compte → ERREUR
        if (hasTvaRate && !hasTvaAccount) {
            throw new IllegalArgumentException(
                    "Le compte TVA est OBLIGATOIRE quand un taux de TVA est fourni. " +
                            "Exemple: tvaAccount='34552' + defaultTvaRate=20.0");
        }

        // CAS 3: Les deux fournis → OK, vérifier cohérence
        if (hasTvaAccount && hasTvaRate) {
            validateTvaRateValue(defaultTvaRate);
        }

        // CAS 4: Aucun des deux → OK (TVA optionnelle)
    }

    /**
     * Vérifie si le taux TVA est valide
     */
    private void validateTvaRateValue(Double rate) {
        // Taux standards au Maroc: 0%, 7%, 10%, 14%, 20%
        if (rate < 0.0 || rate > 100.0) {
            throw new IllegalArgumentException(
                    "Le taux de TVA doit être entre 0% et 100%");
        }

        // Warning si taux non standard (mais pas d'erreur)
        if (!isStandardMoroccanTvaRate(rate)) {
            System.out.println("ATTENTION: Taux de TVA non standard au Maroc: " + rate + "%");
            System.out.println("Taux standards: 0%, 7%, 10%, 14%, 20%");
        }
    }

    /**
     * Vérifie si le taux est un taux standard au Maroc
     */
    private boolean isStandardMoroccanTvaRate(Double rate) {
        return rate == 0.0 || rate == 7.0 || rate == 10.0 ||
                rate == 14.0 || rate == 20.0;
    }

    /**
     * Résumé de la configuration TVA
     */
    public String getTvaSummary() {
        if (tvaAccount == null || defaultTvaRate == null) {
            return "Aucune configuration TVA";
        }
        return String.format("Compte %s - TVA %.0f%%", tvaAccount, defaultTvaRate);
    }

    /**
     * Vérifie si la configuration TVA est complète
     */
    public boolean hasTvaConfiguration() {
        boolean hasAccount = tvaAccount != null && !tvaAccount.isBlank();
        boolean hasRate = defaultTvaRate != null;
        return hasAccount && hasRate;
    }

}
