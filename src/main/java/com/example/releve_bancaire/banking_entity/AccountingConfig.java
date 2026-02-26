package com.example.releve_bancaire.banking_entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounting_config")
@Data
@NoArgsConstructor
public class AccountingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String journal;

    @Column(nullable = false, length = 200)
    private String designation;

    @Column(nullable = false, length = 50)
    private String banque;

    @Column(nullable = false, length = 50)
    private String compteComptable;

    @Column(nullable = false, length = 40)
    private String rib;

    @Column(name = "ttc_enabled", nullable = false)
    private Boolean ttcEnabled = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.ttcEnabled == null) {
            this.ttcEnabled = false;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
