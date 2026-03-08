package com.example.releve_bancaire.entity.template;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TemplateSignature {

    @Enumerated(EnumType.STRING)
    @Column(name = "signature_type", nullable = false)
    private SignatureType signatureType;

    @Column(name = "signature_value", nullable = false)
    private String signatureValue;

    // Validation
    public boolean isValid() {
        if (signatureValue == null || signatureValue.trim().isEmpty()) {
            return false;
        }

        return switch (signatureType) {
            case ICE -> signatureValue.matches("\\d{15}");
            case IF -> signatureValue.matches("\\d{7,10}");
            case RC -> signatureValue.length() >= 3; // RC peut varier en format
            case SUPPLIER -> signatureValue.length() >= 3;
        };
    }

    @Override
    public String toString() {
        return signatureType + ":" + signatureValue;
    }

    public String getNormalizedValue() {
        if (signatureValue == null) {
            return "UNKNOWN";
        }

        return switch (signatureType) {
            case ICE -> {
                // Prendre les 6 derniers chiffres de l'ICE
                if (signatureValue.length() >= 6) {
                    yield "ICE_" + signatureValue.substring(signatureValue.length() - 6);
                }
                yield "ICE_" + signatureValue;
            }
            case IF -> "IF_" + signatureValue;
            case RC -> "RC_" + signatureValue.replaceAll("[^A-Z0-9]", "_");
            case SUPPLIER -> {
                // Nettoyer et limiter à 20 caractères
                String cleaned = signatureValue.toUpperCase()
                        .replaceAll("[^A-Z0-9]", "_")
                        .replaceAll("_{2,}", "_");

                if (cleaned.length() > 20) {
                    yield cleaned.substring(0, 20);
                }
                yield cleaned;
            }
        };
    }

    public String toShortString() {
        if (signatureValue == null) {
            return signatureType + ":null";
        }

        return switch (signatureType) {
            case ICE -> {
                if (signatureValue.length() > 6) {
                    yield signatureType + ":***" + signatureValue.substring(signatureValue.length() - 6);
                }
                yield signatureType + ":" + signatureValue;
            }
            case IF, RC, SUPPLIER -> signatureType + ":" + signatureValue;
        };
    }

    public static TemplateSignature fromIce(String ice) {
        return new TemplateSignature(SignatureType.ICE, ice);
    }

    public static TemplateSignature fromIf(String ifNumber) {
        return new TemplateSignature(SignatureType.IF, ifNumber);
    }

    public static TemplateSignature fromSupplier(String supplier) {
        return new TemplateSignature(SignatureType.SUPPLIER, supplier);
    }

    public static TemplateSignature fromRc(String rc) {
        return new TemplateSignature(SignatureType.RC, rc);
    }
}