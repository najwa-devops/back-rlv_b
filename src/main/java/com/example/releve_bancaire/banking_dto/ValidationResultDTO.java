package com.example.releve_bancaire.banking_dto;

import com.example.releve_bancaire.banking_entity.ContinuityStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO pour les résultats de validation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResultDTO {

    private Long statementId;
    private boolean isFullyValid;

    // Validation des soldes
    private BalanceValidation balanceValidation;

    // Validation de continuité
    private ContinuityValidation continuityValidation;

    // Statistiques transactions
    private TransactionStats transactionStats;

    // Erreurs et avertissements
    private List<String> errors;
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceValidation {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private List<String> infos;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContinuityValidation {
        private Long previousStatementId;
        private ContinuityStatus status;
        private List<String> errors;
        private List<String> warnings;
        private List<String> infos;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionStats {
        private int total;
        private int valid;
        private int errors;
    }
}
