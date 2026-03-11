package com.example.releve_bancaire.centremonetique.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CentreMonetiqueBatchSummaryDTO {
    private Long id;
    private String filename;
    private String originalName;
    private String rib;
    private String status;
    private String structure;
    private String statementPeriod;
    private String totalTransactions;
    private String totalMontant;
    private String totalCommissionHt;
    private String totalTvaSurCommissions;
    private String soldeNetRemise;
    private String totalDebit;
    private String totalCredit;
    private Integer transactionCount;
    private String createdAt;
    private String updatedAt;
    private String errorMessage;
}
