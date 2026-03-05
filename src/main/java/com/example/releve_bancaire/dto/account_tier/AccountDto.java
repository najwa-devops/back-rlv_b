package com.example.releve_bancaire.dto.account_tier;

import com.example.releve_bancaire.account_tier.Account;
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
     * Convertit une entité Account en DTO
     */
    public static AccountDto fromEntity(Account entity) {
        if (entity == null)
            return null;

        return AccountDto.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .libelle(entity.getLibelle())
                .classe(entity.getClasse())
                .classeName(entity.getClasseName())
                .tvaRate(entity.getTvaRate())
                .active(entity.getActive())
                .isFournisseurAccount(entity.isFournisseurAccount())
                .isChargeAccount(entity.isChargeAccount())
                .isTvaAccount(entity.isTvaAccount())
                .displayWithTva(entity.getDisplayWithTva())
                .tvaDescription(entity.getTvaDescription())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    public String getShortDisplay() {
        if (tvaRate != null && tvaRate > 0) {
            return String.format("%s - TVA %.0f%%", code, tvaRate);
        }
        return String.format("%s - %s", code, libelle);
    }

}
