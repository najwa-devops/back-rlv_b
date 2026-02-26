package com.example.releve_bancaire.banking_entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "bank_transaction_account_rule", indexes = {
        @Index(name = "idx_bank_tx_rule_norm", columnList = "normalized_libelle", unique = true),
        @Index(name = "idx_bank_tx_rule_compte", columnList = "account_code")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankTransactionAccountRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "normalized_libelle", nullable = false, unique = true, length = 400)
    private String normalizedLibelle;

    @Column(name = "example_libelle", length = 1000)
    private String exampleLibelle;

    @Column(name = "account_code", nullable = false, length = 20)
    private String accountCode;

    @Column(name = "usage_count", nullable = false)
    private Integer usageCount = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (usageCount == null || usageCount < 1) {
            usageCount = 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
