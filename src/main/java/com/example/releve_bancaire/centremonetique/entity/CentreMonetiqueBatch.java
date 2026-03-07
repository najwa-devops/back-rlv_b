package com.example.releve_bancaire.centremonetique.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cm_batch")
@Data
@NoArgsConstructor
public class CentreMonetiqueBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(nullable = false, length = 500)
    private String originalName;

    @Column(length = 120)
    private String fileContentType;

    @Column
    private Long fileSize;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_data", columnDefinition = "LONGBLOB")
    private byte[] fileData;

    @Lob
    @Column(name = "raw_ocr_text", columnDefinition = "LONGTEXT")
    private String rawOcrText;

    @Column(nullable = false, length = 30)
    private String status = "PENDING";

    @Column(length = 500)
    private String errorMessage;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalMontant;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalDebit;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalCredit;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalCommissionHt;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalTvaSurCommissions;

    @Column(precision = 15, scale = 2)
    private BigDecimal soldeNetRemise;

    @Column(length = 30)
    private String statementPeriod;

    @Column(nullable = false, length = 30)
    private String structure = "AUTO";

    @Column(nullable = false)
    private Integer transactionCount = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("rowIndex ASC")
    private List<CentreMonetiqueTransaction> transactions = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void addTransaction(CentreMonetiqueTransaction transaction) {
        transactions.add(transaction);
        transaction.setBatch(this);
    }
}
