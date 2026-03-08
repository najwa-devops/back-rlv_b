package com.example.releve_bancaire.dto.account_tier;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAccountRequest {

    @NotBlank(message = "Le code du compte est obligatoire")
    @Pattern(regexp = "^\\d{4,10}$", message = "Le code doit contenir entre 4 et 10 chiffres")
    private String code;

    @NotBlank(message = "Le libellé est obligatoire")
    @Size(min = 3, max = 200, message = "Le libellé doit contenir entre 3 et 200 caractères")
    private String libelle;

    @DecimalMin(value = "0.0", message = "Le taux de TVA doit être positif ou nul")
    @DecimalMax(value = "100.0", message = "Le taux de TVA ne peut pas dépasser 100%")
    private Double tvaRate;

    private Boolean active;

    private String createdBy;

    public void validate() {
        if (isTvaAccount() && !hasTvaRate()) {
            throw new IllegalArgumentException(
                    "COMPTE TVA: Le taux de TVA est OBLIGATOIRE pour les comptes TVA (3455X). " +
                            "Exemple: code='34552' + tvaRate=20.0");
        }

        if (!isTvaAccount() && hasTvaRate()) {
            System.out.println("  ATTENTION: Taux TVA fourni pour un compte non-TVA (" + code + ")");
            System.out.println("  Le taux sera enregistré mais rarement utilisé.");
        }
    }

    private boolean isTvaAccount() {
        return code != null && code.startsWith("3455");
    }

    private boolean hasTvaRate() {
        return tvaRate != null;
    }

    public String getSummary() {
        if (hasTvaRate()) {
            return String.format("Account[code=%s, libelle='%s', tvaRate=%.0f%%]",
                    code, libelle, tvaRate);
        }
        return String.format("Account[code=%s, libelle='%s']", code, libelle);
    }
}
