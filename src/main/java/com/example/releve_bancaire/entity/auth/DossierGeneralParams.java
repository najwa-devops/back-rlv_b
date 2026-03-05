package com.example.releve_bancaire.entity.auth;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "dossier_general_params", uniqueConstraints = @UniqueConstraint(name = "uk_dossier_general_params_dossier", columnNames = "dossier_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DossierGeneralParams {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    private Dossier dossier;

    @Column(name = "dossier_id", insertable = false, updatable = false)
    private Long dossierId;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "legal_form", length = 150)
    private String legalForm;

    @Column(name = "rc_number", length = 80)
    private String rcNumber;

    @Column(name = "if_number", length = 80)
    private String ifNumber;

    @Column(name = "tsc", length = 80)
    private String tsc;

    @Column(name = "activity", length = 255)
    private String activity;

    @Column(name = "category", length = 255)
    private String category;

    @Column(name = "professional_tax", length = 80)
    private String professionalTax;

    @Column(name = "ice", length = 80)
    private String ice;

    @Column(name = "cni_or_residence_card", length = 120)
    private String cniOrResidenceCard;

    @Column(name = "legal_representative", length = 200)
    private String legalRepresentative;

    @Column(name = "cm_rate", precision = 10, scale = 2)
    private BigDecimal cmRate;

    @Column(name = "is_rate", precision = 10, scale = 2)
    private BigDecimal isRate;

    @Column(name = "capital", precision = 18, scale = 2)
    private BigDecimal capital;

    @Column(name = "subject_to_ras")
    private Boolean subjectToRas = false;

    @Column(name = "individual_person")
    private Boolean individualPerson = false;

    @Column(name = "has_fiscal_regularity_certificate")
    private Boolean hasFiscalRegularityCertificate = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (subjectToRas == null)
            subjectToRas = false;
        if (individualPerson == null)
            individualPerson = false;
        if (hasFiscalRegularityCertificate == null)
            hasFiscalRegularityCertificate = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
