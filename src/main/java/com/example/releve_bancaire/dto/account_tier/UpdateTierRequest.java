package com.example.releve_bancaire.dto.account_tier;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO pour la mise à jour d'un tier
 *
 * TOUS LES CHAMPS OPTIONNELS (mise à jour partielle)
 *
 * NOTE: Le mode auxiliaire NE PEUT PAS être modifié après création
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateTierRequest {

        /**
         * Numéro de tier (9 chiffres)
         * OPTIONNEL
         */
        @Size(max = 31, message = "Le numéro de tier ne doit pas dépasser 20 caractères")
        @Pattern(regexp = "^[A-Z0-9\\-]+$|^$", message = "Le numéro de tier ne peut contenir que des lettres majuscules, chiffres et tirets")
        private String tierNumber;

        /**
         * Compte collectif (9 chiffres)
         * OPTIONNEL
         * Valeurs: "342100000" ou "441100000"
         */
        @Pattern(regexp = "^(342100000|441100000)$|^$", message = "Le compte collectif doit être '342100000' ou '441100000'")
        private String collectifAccount;

        /**
         * Libellé
         * OPTIONNEL
         */
        @Size(min = 2, max = 200, message = "Le libellé doit contenir entre 2 et 200 caractères")
        private String libelle;

        /**
         * IF (7-10 chiffres)
         * OPTIONNEL
         */
        @Pattern(regexp = "^\\d{7,10}$|^$", message = "L'IF doit contenir entre 7 et 10 chiffres")
        private String ifNumber;

        /**
         * ICE (15 chiffres)
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

        /**
         * Compte de charge (9 chiffres)
         * OPTIONNEL
         */
        @Pattern(regexp = "^\\d{9}$|^$", message = "Le compte de charge doit contenir exactement 9 chiffres")
        private String defaultChargeAccount;

        /**
         * Compte TVA (9 chiffres)
         * OPTIONNEL
         */
        @Pattern(regexp = "^\\d{9}$|^$", message = "Le compte TVA doit contenir exactement 9 chiffres")
        private String tvaAccount;

        /**
         * Taux de TVA
         * OPTIONNEL
         */
        @Min(value = 0, message = "Le taux de TVA doit être positif")
        @Max(value = 100, message = "Le taux de TVA ne peut pas dépasser 100%")
        private Double defaultTvaRate;

        /**
         * Statut actif
         * OPTIONNEL
         */
        private Boolean active;

        /**
         * Utilisateur modificateur
         */
        private String updatedBy;

        public void validate() {
                boolean hasTvaAccount = tvaAccount != null && !tvaAccount.isBlank();
                boolean hasTvaRate = defaultTvaRate != null;

                // Si un seul des deux fourni → ERREUR
                if (hasTvaAccount != hasTvaRate) {
                        throw new IllegalArgumentException(
                                        "Le compte TVA et le taux TVA doivent être modifiés ENSEMBLE. " +
                                                        "Fournissez les deux ou aucun des deux.");
                }
        }

}
