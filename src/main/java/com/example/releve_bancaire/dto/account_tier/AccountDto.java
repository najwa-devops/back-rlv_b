package com.example.releve_bancaire.dto.account_tier;

import com.example.releve_bancaire.account_tier.Compte;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDto {

    private Long id;
    private String code;
    private String libelle;
    private Integer classe;
    private String classeName;
    private Double tvaRate;
    private Boolean active;

    // Flags de type de compte
    private Boolean isFournisseurAccount;
    private Boolean isChargeAccount;
    private Boolean isTvaAccount;
    private String displayWithTva;
    private String tvaDescription;

    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    /**
     * Convertit une entité Compte en DTO
     */
    public static AccountDto fromEntity(Compte entity) {
        if (entity == null)
            return null;

        return AccountDto.builder()
                .code(entity.getNumero())
                .libelle(entity.getLibelle())
                .classe(entity.getClasse())
                .classeName(entity.getClasseName())
                .tvaRate(entity.getTaux())
                .active(entity.getActive())
                .isFournisseurAccount(entity.isFournisseurAccount())
                .isChargeAccount(entity.isChargeAccount())
                .isTvaAccount(entity.isTvaAccount())
                .displayWithTva(entity.getDisplayWithTaux())
                .tvaDescription(entity.getTvaDescription())
                .createdAt(entity.getDatcree())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public String getShortDisplay() {
        if (tvaRate != null && tvaRate > 0) {
            return String.format("%s - TVA %.0f%%", code, tvaRate);
        }
        return String.format("%s - %s", code, libelle);
    }

}
