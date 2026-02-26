package com.example.releve_bancaire.account_tier;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts",
        indexes = {
                @Index(name = "idx_account_code", columnList = "code", unique = true),
                @Index(name = "idx_account_classe", columnList = "classe"),
                @Index(name = "idx_account_libelle", columnList = "libelle"),
                @Index(name = "idx_account_active", columnList = "active")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 20)
    @NotBlank(message = "Le code du compte est obligatoire")
    private String code;

    @Column(name = "libelle", nullable = false, length = 200)
    @NotBlank(message = "Le libellé est obligatoire")
    private String libelle;

    @Column(name = "classe", nullable = false)
    @NotNull(message = "La classe est obligatoire")
    private Integer classe;

    @Column(name = "tva_rate")
    private Double tvaRate;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    // ===================== LIFECYCLE =====================

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (active == null) {
            active = true;
        }
        // Auto-dériver la classe depuis le code
        if (classe == null && code != null && !code.isBlank()) {
            deriveClasseFromCode();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ===================== BUSINESS METHODS =====================
    public void deriveClasseFromCode() {
        if (code != null && !code.isBlank() && code.matches("^\\d.*")) {
            try {
                this.classe = Integer.parseInt(code.substring(0, 1));
            } catch (Exception e) {
                // Si erreur, classe = 0 (invalide)
                this.classe = 0;
            }
        }
    }

    public String getClasseName() {
        if (classe == null) return "Inconnu";
        return switch (classe) {
            case 1 -> "Financement permanent";
            case 2 -> "Actif immobilisé";
            case 3 -> "Actif circulant";
            case 4 -> "Passif circulant";
            case 5 -> "Trésorerie";
            case 6 -> "Charges";
            case 7 -> "Produits";
            case 8 -> "Résultats";
            default -> "Inconnu";
        };
    }

    public boolean isFournisseurAccount() {
        return code != null && code.startsWith("441");
    }

    public boolean isChargeAccount() {
        return classe != null && classe == 6;
    }

    public boolean isTvaAccount() {
        return classe != null && classe == 3 && code.startsWith("3455");
    }

    public boolean hasTvaRate() {
        return tvaRate != null;
    }

    public String getDisplayWithTva() {
        if (hasTvaRate()) {
            return String.format("%s - %s (Taux: %.0f%%)", code, libelle, tvaRate);
        }
        return String.format("%s - %s", code, libelle);
    }

    public String getTvaDescription() {
        if (!hasTvaRate()) {
            return "Pas de taux TVA";
        }

        if (tvaRate == 0.0) {
            return "TVA 0% (Exonéré)";
        }

        return String.format("TVA %.0f%%", tvaRate);
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public boolean isValidCode() {
        return code != null
                && code.matches("^\\d{4,10}$")  // 4 à 10 chiffres
                && classe != null
                && classe >= 1
                && classe <= 8;
    }

    @Override
    public String toString() {
        return "Account{" +
                "id=" + id +
                ", code='" + code + '\'' +
                ", libelle='" + libelle + '\'' +
                ", classe=" + classe +
                ", active=" + active +
                '}';
    }

}
