package com.example.releve_bancaire.accounting_entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounting_entry", indexes = {
        @Index(name = "idx_acc_entry_journal_month_numero", columnList = "ndosjrn,nmois,numero", unique = true),
        @Index(name = "idx_acc_entry_date_complete", columnList = "date_complete"),
        @Index(name = "idx_acc_entry_statement_tx", columnList = "source_statement_id,source_transaction_id")
})
@Data
@NoArgsConstructor
public class AccountingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long numero;

    @Column(nullable = false, length = 20)
    private String mois;

    @Column(nullable = false)
    private Integer nmois;

    @Column(name = "date_complete", nullable = false)
    private LocalDate dateComplete;

    @Column(nullable = false, length = 1000)
    private String ecriture;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    private String ncompte;

    @Column(name = "jour", nullable = false)
    private Integer date;

    @Column(nullable = false, length = 30)
    private String ndosjrn;

    @Column(name = "source_statement_id")
    private Long sourceStatementId;

    @Column(name = "source_transaction_id")
    private Long sourceTransactionId;

    @Column(name = "is_counterpart", nullable = false)
    private Boolean isCounterpart = false;

    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (debit == null) {
            debit = BigDecimal.ZERO;
        }
        if (credit == null) {
            credit = BigDecimal.ZERO;
        }
    }
}
