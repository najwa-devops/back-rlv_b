package com.example.releve_bancaire.releve_bancaire.mapper;

import com.example.releve_bancaire.releve_bancaire.dto.BankStatementDTO;
import com.example.releve_bancaire.releve_bancaire.dto.BankStatementDetailDTO;
import com.example.releve_bancaire.releve_bancaire.entity.BankStatement;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Mapper pour BankStatement
 */
@Component
public class BankStatementMapper {

    private final BankTransactionMapper transactionMapper;

    public BankStatementMapper(BankTransactionMapper transactionMapper) {
        this.transactionMapper = transactionMapper;
    }

    /**
     * Convertit une entité en DTO simple
     */
    public BankStatementDTO toDTO(BankStatement entity) {
        if (entity == null) {
            return null;
        }

        return BankStatementDTO.builder()
                .id(entity.getId())
                .filename(entity.getFilename())
                .originalName(entity.getOriginalName())
                .rib(entity.getRib())
                .month(entity.getMonth())
                .year(entity.getYear())
                .bankName(entity.getBankName())
                .openingBalance(entity.getOpeningBalance())
                .closingBalance(entity.getClosingBalance())
                .totalCredit(entity.getTotalCredit())
                .totalDebit(entity.getTotalDebit())
                .totalCreditPdf(entity.getTotalCreditPdf())
                .totalDebitPdf(entity.getTotalDebitPdf())
                .balanceDifference(entity.getBalanceDifference())
                .verificationStatus(entity.getVerificationStatus())
                .status(entity.getStatus())
                .continuityStatus(entity.getContinuityStatus())
                .isBalanceValid(entity.getIsBalanceValid())
                .isContinuityValid(entity.getIsContinuityValid())
                .transactionCount(entity.getTransactionCount())
                .validTransactionCount(entity.getValidTransactionCount())
                .errorTransactionCount(entity.getErrorTransactionCount())
                .overallConfidence(entity.getOverallConfidence())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .validatedAt(entity.getValidatedAt())
                .fileSize(entity.getFileSize())
                .build();
    }

    /**
     * Convertit une entité en DTO détaillé (avec transactions)
     */
    public BankStatementDetailDTO toDetailDTO(BankStatement entity, boolean includeTransactions) {
        if (entity == null) {
            return null;
        }

        BankStatementDetailDTO dto = BankStatementDetailDTO.builder()
                .id(entity.getId())
                .filename(entity.getFilename())
                .originalName(entity.getOriginalName())
                .rib(entity.getRib())
                .month(entity.getMonth())
                .year(entity.getYear())
                .bankName(entity.getBankName())
                .accountHolder(entity.getAccountHolder())
                .openingBalance(entity.getOpeningBalance())
                .closingBalance(entity.getClosingBalance())
                .totalCredit(entity.getTotalCredit())
                .totalDebit(entity.getTotalDebit())
                .balanceDifference(entity.getBalanceDifference())
                .status(entity.getStatus())
                .continuityStatus(entity.getContinuityStatus())
                .isBalanceValid(entity.getIsBalanceValid())
                .isContinuityValid(entity.getIsContinuityValid())
                .validationErrors(entity.getValidationErrors())
                .transactionCount(entity.getTransactionCount())
                .validTransactionCount(entity.getValidTransactionCount())
                .errorTransactionCount(entity.getErrorTransactionCount())
                .overallConfidence(entity.getOverallConfidence())
                .filePath(entity.getFilePath())
                .fileSize(entity.getFileSize())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .validatedAt(entity.getValidatedAt())
                .validatedBy(entity.getValidatedBy())
                .rawOcrText(entity.getRawOcrText())
                .cleanedOcrText(entity.getCleanedOcrText())
                .build();

        if (includeTransactions && entity.getTransactions() != null) {
            dto.setTransactions(
                    entity.getTransactions().stream()
                            .map(transactionMapper::toDTO)
                            .collect(Collectors.toList()));
        }

        return dto;
    }

    /**
     * Convertit une entité en DTO détaillé avec preview des transactions
     */
    public BankStatementDetailDTO toDetailDTOWithPreview(BankStatement entity, int previewCount) {
        if (entity == null) {
            return null;
        }

        BankStatementDetailDTO dto = toDetailDTO(entity, false);

        if (entity.getTransactions() != null && !entity.getTransactions().isEmpty()) {
            dto.setTransactions(
                    entity.getTransactions().stream()
                            .limit(previewCount)
                            .map(transactionMapper::toDTO)
                            .collect(Collectors.toList()));
            dto.setTransactionsPreviewCount(previewCount);
        }

        return dto;
    }
}
