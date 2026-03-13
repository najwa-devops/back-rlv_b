package com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.dto;

import java.util.List;

public record CmExpansionDTO(
        Long bankTransactionId,
        Long cmBatchId,
        String cmBatchOriginalName,
        String cmReference,
        String cmMontant,
        String commissionHt,        // TOTAL COMMISSIONS HT — DÉBIT, compte 614700000
        String tvaSurCommissions,   // TOTAL TVA SUR COMMISSIONS — DÉBIT, compte 345520100
        List<CmExpansionLineDTO> lines
) {}
