package com.example.releve_bancaire.dto.account_tier;

import com.example.releve_bancaire.entity.account_tier.Tier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO de réponse pour un Tier
 * Contient tous les champs + champs calculés
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TierDto {

    // ===================== IDENTIFICATION =====================

    private Long id;
    private Long dossierId;
    private String libelle;

    // ===================== MODE AUXILIAIRE =====================

    /**
     * Mode auxiliaire activé/désactivé
     */
    private Boolean auxiliaireMode;

    /**
     * Numéro de tier (TOUJOURS présent)
     * 9 chiffres
     */
    private String tierNumber;

    /**
     * Compte collectif (si mode auxiliaire)
     * 9 chiffres: "342100000" ou "441100000"
     */
    private String collectifAccount;

    /**
     * Compte pour affichage (calculé)
     *
     * Si mode auxiliaire: "441100000 / 000000123"
     * Si mode normal: "000000123"
     */
    private String displayAccount;

    // ===================== IDENTIFIANTS FISCAUX =====================

    private String ifNumber;
    private String ice;
    private String rcNumber;

    /**
     * Type d'identifiant principal: "ICE", "IF" ou "NONE"
     */
    private String identifierType;

    /**
     * Identifiant principal (ICE prioritaire sur IF)
     */
    private String primaryIdentifier;

    // ===================== CONFIGURATION COMPTABLE =====================

    /**
     * Compte de charge par défaut (9 chiffres)
     */
    private String defaultChargeAccount;

    /**
     * Compte TVA (9 chiffres)
     */
    private String tvaAccount;

    /**
     * Taux de TVA par défaut
     */
    private Double defaultTvaRate;

    /**
     * A une configuration comptable complète ?
     */
    private Boolean hasAccountingConfig;

    /**
     * Résumé de la configuration comptable
     */
    private String accountingConfigSummary;

    // ===================== STATUT ET AUDIT =====================

    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    // ===================== CONVERSION =====================

    private String tvaDisplayFormat;
    private Boolean hasTvaConfiguration;

    /**
     * Convertit une entité Tier en DTO
     */
    public static TierDto fromEntity(Tier tier) {
        if (tier == null) {
            return null;
        }

        return TierDto.builder()
                // Identification
                .id(tier.getId())
                .dossierId(tier.getDossierId())
                .libelle(tier.getLibelle())

                // Mode auxiliaire
                .auxiliaireMode(tier.getAuxiliaireMode())
                .tierNumber(tier.getTierNumber())
                .collectifAccount(tier.getCollectifAccount())
                .displayAccount(tier.getDisplayAccount())

                // Identifiants fiscaux
                .ifNumber(tier.getIfNumber())
                .ice(tier.getIce())
                .rcNumber(tier.getRcNumber())
                .identifierType(tier.getIdentifierType())
                .primaryIdentifier(tier.getPrimaryIdentifier())

                // Configuration comptable
                .defaultChargeAccount(tier.getDefaultChargeAccount())
                .tvaAccount(tier.getTvaAccount())
                .defaultTvaRate(tier.getDefaultTvaRate())
                .tvaDisplayFormat(tier.getTvaDisplayFormat())
                .hasTvaConfiguration(tier.hasTvaConfiguration())
                .hasAccountingConfig(tier.hasAccountingConfiguration())
                .accountingConfigSummary(tier.getAccountingConfigSummary())

                // Statut et audit
                .active(tier.getActive())
                .createdAt(tier.getCreatedAt())
                .updatedAt(tier.getUpdatedAt())
                .createdBy(tier.getCreatedBy())
                .updatedBy(tier.getUpdatedBy())

                .build();
    }

    public String getShortSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(libelle);

        if (ifNumber != null && !ifNumber.isBlank()) {
            sb.append(" (IF: ").append(ifNumber).append(")");
        } else if (ice != null && !ice.isBlank()) {
            sb.append(" (ICE: ").append(ice).append(")");
        }

        return sb.toString();
    }

    public String getFullSummary() {
        return String.format(
                "Tier[id=%d, libelle='%s', tierNumber='%s', IF='%s', ICE='%s', tva='%s']",
                id, libelle, tierNumber, ifNumber, ice, tvaDisplayFormat);
    }
}
