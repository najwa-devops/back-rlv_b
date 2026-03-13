package com.example.releve_bancaire.centre_monetique.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "cm_transaction")
@Data
@NoArgsConstructor
public class CentreMonetiqueTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private CentreMonetiqueBatch batch;

    @Column(name = "row_index")
    private Integer rowIndex;

    @Column(nullable = false, length = 64)
    private String section;

    @Column(length = 16)
    private String date;

    @Column(length = 32)
    private String reference;

    @Column(name = "dc_flag", length = 16)
    private String dcFlag;

    @Column(precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(precision = 15, scale = 4)
    private BigDecimal debit;

    @Column(precision = 15, scale = 4)
    private BigDecimal credit;
}
