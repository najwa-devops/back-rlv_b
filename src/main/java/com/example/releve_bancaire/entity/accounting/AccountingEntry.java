package com.example.releve_bancaire.entity.accounting;

import com.example.releve_bancaire.entity.auth.Dossier;
import com.example.releve_bancaire.entity.dynamic.DynamicInvoice;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity(name = "AccountingEntryArchive")
@Table(name = "accounting_entries", indexes = {
        @Index(name = "idx_accounting_entries_dossier", columnList = "dossier_id"),
        @Index(name = "idx_accounting_entries_invoice", columnList = "invoice_id"),
        @Index(name = "idx_accounting_entries_date", columnList = "entry_date"),
        @Index(name = "idx_accounting_entries_account", columnList = "account_number")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id")
    private Dossier dossier;

    @Column(name = "dossier_id", insertable = false, updatable = false)
    private Long dossierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private DynamicInvoice invoice;

    @Column(name = "invoice_id", insertable = false, updatable = false)
    private Long invoiceId;

    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    @Column(name = "supplier_name", length = 200)
    private String supplierName;

    @Column(name = "journal", length = 50)
    private String journal;

    @Column(name = "account_number", length = 50, nullable = false)
    private String accountNumber;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "debit_amount", precision = 15, scale = 2)
    private BigDecimal debit;

    @Column(name = "credit_amount", precision = 15, scale = 2)
    private BigDecimal credit;

    @Column(name = "label", length = 200)
    private String label;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (entryDate == null) {
            entryDate = LocalDate.now();
        }
    }
}
