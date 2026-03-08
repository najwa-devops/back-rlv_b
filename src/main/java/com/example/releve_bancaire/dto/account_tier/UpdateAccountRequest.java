package com.example.releve_bancaire.dto.account_tier;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateAccountRequest {

    /**
     * Nouveau libellé du compte
     */
    @Size(min = 3, max = 200, message = "Le libellé doit contenir entre 3 et 200 caractères")
    private String libelle;

    /**
     * Statut actif ou non
     */
    private Boolean active;

    /**
     * Utilisateur qui modifie
     */
    private String updatedBy;
}
