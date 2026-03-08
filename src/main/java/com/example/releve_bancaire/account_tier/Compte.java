package com.example.releve_bancaire.account_tier;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity(name = "Compte")
@Table(name = "comptes",
        indexes = {
                @Index(name = "idx_compte_numero", columnList = "numero", unique = true),
                @Index(name = "idx_compte_classe", columnList = "classe"),
                @Index(name = "idx_compte_libelle", columnList = "libelle"),
                @Index(name = "idx_compte_active", columnList = "active")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Compte {

    @Id
    @Column(name = "numero", nullable = false, unique = true, length = 20)
    @NotBlank(message = "Le numéro du compte est obligatoire")
    private String numero;

    @Column(name = "libelle", nullable = false, length = 200)
    @NotBlank(message = "Le libellé est obligatoire")
    private String libelle;

    @Column(name = "classe", nullable = false)
    @NotNull(message = "La classe est obligatoire")
    private Integer classe;

    @Column(name = "taux")
    private Double taux;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "x_act", length = 1)
    private String xAct;

    @Column(name = "categorie", length = 50)
    private String categorie;

    @Column(name = "typcpte", length = 20)
    private String typcpte;

    @Column(name = "type_cmpt", length = 20)
    private String typeCmpt;

    @Column(name = "datcree")
    private LocalDateTime datcree;

    @Column(name = "fin_at")
    private LocalDateTime finAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "ice", length = 50)
    private String ice;

    @Column(name = "rc", length = 50)
    private String rc;

    @Column(name = "rib", length = 50)
    private String rib;

    @Column(name = "tva", length = 50)
    private String tva;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "gsm", length = 20)
    private String gsm;

    @Column(name = "tel1", length = 20)
    private String tel1;

    @Column(name = "adresse", length = 255)
    private String adresse;

    @Column(name = "ville", length = 100)
    private String ville;

    @Column(name = "pays", length = 100)
    private String pays;

    // ===================== LIFECYCLE =====================

    @PrePersist
    protected void onCreate() {
        datcree = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (active == null) {
            active = true;
        }
        if (xAct == null) {
            xAct = "1";
        }
        // Auto-dériver la classe depuis le numero
        if (classe == null && numero != null && !numero.isBlank()) {
            deriveClasseFromNumero();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ===================== BUSINESS METHODS =====================
    public void deriveClasseFromNumero() {
        if (numero != null && !numero.isBlank() && numero.matches("^\\d.*")) {
            try {
                this.classe = Integer.parseInt(numero.substring(0, 1));
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
        return numero != null && numero.startsWith("441");
    }

    public boolean isChargeAccount() {
        return classe != null && classe == 6;
    }

    public boolean isTvaAccount() {
        return classe != null && classe == 3 && numero.startsWith("3455");
    }

    public boolean hasTaux() {
        return taux != null;
    }

    public String getDisplayWithTaux() {
        if (hasTaux()) {
            return String.format("%s - %s (Taux: %.0f%%)", numero, libelle, taux);
        }
        return String.format("%s - %s", numero, libelle);
    }

    public String getTvaDescription() {
        if (!hasTaux()) {
            return "Pas de taux";
        }

        if (taux == 0.0) {
            return "Taux 0% (Exonéré)";
        }

        return String.format("Taux %.0f%%", taux);
    }

    public void activate() {
        this.active = true;
        this.xAct = "1";
    }

    public void deactivate() {
        this.active = false;
        this.xAct = "0";
    }

    public boolean isValidNumero() {
        return numero != null
                && numero.matches("^\\d{4,10}$")
                && classe != null
                && classe >= 1
                && classe <= 8;
    }

    @Override
    public String toString() {
        return "Compte{" +
                "id=" + id +
                ", numero='" + numero + '\'' +
                ", libelle='" + libelle + '\'' +
                ", classe=" + classe +
                ", active=" + active +
                '}';
    }

}
