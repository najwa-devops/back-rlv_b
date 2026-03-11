package com.example.releve_bancaire.centremonetique.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RapprochementResultDTO {

    private Long batchId;
    private String batchRib;
    private int totalCmTransactions;
    private int matchedCount;
    private List<RapprochementMatchDTO> matches;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RapprochementMatchDTO {
        /** Date de l'opération (pour le regroupement par rowspan). */
        private String date;
        /** Référence du groupe CM (ex: "4 transactions CM" ou référence unique). */
        private String cmReference;
        /** Montant total du groupe CM pour cette date. */
        private String cmMontant;
        /** STAN / référence individuelle de la transaction CM. */
        private String cmStan;
        /** Type de carte : V (Visa) ou M (Mastercard). */
        private String cmType;
        /** Montant de la transaction CM individuelle. */
        private String cmMontantTransaction;
        /** Nom(s) du/des relevé(s) bancaire(s) ayant une ligne à cette date. */
        private String bankStatementName;
        /** Montant total côté relevé bancaire (crédit) pour cette date. */
        private String bankMontant;
        /** Libellé(s) de la/des transaction(s) bancaire(s) pour cette date. */
        private String bankLibelle;
    }
}
