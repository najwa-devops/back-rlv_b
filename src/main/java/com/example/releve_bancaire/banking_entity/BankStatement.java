package com.example.releve_bancaire.banking_entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bank_statement")
@Data
@NoArgsConstructor
public class BankStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==================== FICHIER ====================

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(nullable = false, length = 500)
    private String originalName;

    @Column(nullable = false, length = 500)
    private String filePath;

    @Column
    private Long fileSize;

    // OCR externalisé (filesystem / S3 / MinIO)
    @Column(length = 500)
    private String ocrFilePath;

    @Column(columnDefinition = "TEXT")
    private String rawOcrText;

    @Column(columnDefinition = "TEXT")
    private String cleanedOcrText;

    // ==================== MÉTADONNÉES ====================

    @Column(length = 30)
    private String rib;

    @Column
    private Integer month; // 1-12

    @Column
    private Integer year;

    @Column(length = 100)
    private String bankName;

    @Column(length = 255)
    private String accountHolder;

    @Column(name = "apply_ttc_rule", nullable = false)
    private Boolean applyTtcRule = false;

    // ==================== SOLDES ====================

    @Column(precision = 15, scale = 2)
    private BigDecimal openingBalance;

    @Column(precision = 15, scale = 2)
    private BigDecimal closingBalance;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalCredit;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalDebit;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalCreditPdf;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalDebitPdf;

    @Column(precision = 15, scale = 2)
    private BigDecimal balanceDifference;

    @Column(length = 20)
    private String verificationStatus;

    // ==================== VALIDATION ====================

    @Column(nullable = false, length = 20)
    @Convert(converter = BankStatusConverter.class)
    private BankStatus status = BankStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private ContinuityStatus continuityStatus;

    @Column
    private Boolean isBalanceValid;

    @Column
    private Boolean isContinuityValid;

    @Column(columnDefinition = "TEXT")
    private String validationErrors;

    @Column
    private Double overallConfidence;

    @Column(length = 128)
    private String duplicateHash;

    @Column(nullable = false)
    private Boolean isDuplicate = false;

    // ==================== STATISTIQUES (PRÉ-CALCULÉES) ====================

    @Column(nullable = false)
    private Integer transactionCount = 0;

    @Column(nullable = false)
    private Integer validTransactionCount = 0;

    @Column(nullable = false)
    private Integer errorTransactionCount = 0;

    // ==================== RELATION ====================
    // PAS DE CASCADE ALL (très important pour gros volumes)
    @OneToMany(mappedBy = "statement", fetch = FetchType.LAZY)
    @OrderBy("transactionIndex ASC")
    private List<BankTransaction> transactions = new ArrayList<>();

    // ==================== DATES ====================

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime validatedAt;

    @Column(length = 50)
    private String validatedBy;

    @Column
    private LocalDateTime accountedAt;

    @Column(length = 50)
    private String accountedBy;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== MÉTIER ====================

    public boolean isModifiable() {
        return status != BankStatus.VALIDATED && status != BankStatus.COMPTABILISE;
    }

    public boolean canBeValidated() {
        return status == BankStatus.READY_TO_VALIDATE ||
                status == BankStatus.TREATED;
    }

    public void validate(String userId) {
        if (!isModifiable()) {
            throw new IllegalStateException("Relevé déjà validé");
        }
        this.status = BankStatus.VALIDATED;
        this.validatedAt = LocalDateTime.now();
        this.validatedBy = userId;
    }

    public void markAsAccounted(String userId) {
        if (status != BankStatus.VALIDATED && status != BankStatus.COMPTABILISE) {
            throw new IllegalStateException("Le relevé doit être VALIDATED pour être comptabilisé");
        }
        this.status = BankStatus.COMPTABILISE;
        this.accountedAt = LocalDateTime.now();
        this.accountedBy = userId;
    }

    public BigDecimal calculateExpectedClosingBalance() {
        if (openingBalance == null)
            return null;

        return openingBalance
                .add(totalCredit != null ? totalCredit : BigDecimal.ZERO)
                .subtract(totalDebit != null ? totalDebit : BigDecimal.ZERO);
    }

    public void calculateTotalsFromTransactions() {
        this.totalCredit = transactions.stream()
                .map(BankTransaction::getCredit)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.totalDebit = transactions.stream()
                .map(BankTransaction::getDebit)
                .filter(d -> d != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void updateTransactionCounters() {
        this.transactionCount = transactions.size();
        this.validTransactionCount = (int) transactions.stream()
                .filter(t -> t.getIsValid() != null && t.getIsValid())
                .count();
        this.errorTransactionCount = (int) transactions.stream()
                .filter(t -> t.getNeedsReview() != null && t.getNeedsReview())
                .count();
    }

    public void addTransaction(BankTransaction transaction) {
        transactions.add(transaction);
        transaction.setStatement(this);
    }
}
