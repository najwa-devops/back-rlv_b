package com.example.releve_bancaire.dto.dynamic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request pour créer un template dynamique
 * VERSION AVEC CHOIX EXPLICITE DE LA SIGNATURE
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDynamicTemplateRequest {

    @NotBlank(message = "Le nom du template est requis")
    private String templateName;

    private String supplierType;

    @NotNull(message = "La signature est requise")
    @Valid
    private SignatureRequest signature;

    private FixedSupplierDataRequest fixedSupplierData;
    private String description;
    private String createdBy;
    private List<FieldDefinitionRequest> fieldDefinitions;

    // ==================== SIGNATURE REQUEST ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SignatureRequest {
        @NotBlank(message = "Le type de signature est requis (IF ou ICE)")
        private String type;

        @NotBlank(message = "La valeur de la signature est requise")
        private String value;

        public void validate() {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("Type de signature requis");
            }

            String upperType = type.toUpperCase();
            if (!upperType.equals("IF") && !upperType.equals("ICE") && !upperType.equals("RC")) {
                throw new IllegalArgumentException("Type de signature invalide. Attendu: IF, ICE ou RC");
            }

            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Valeur de signature requise");
            }

            if (upperType.equals("IF")) {
                if (!value.matches("\\d{7,10}")) {
                    throw new IllegalArgumentException("IF invalide. Format attendu: 7-10 chiffres");
                }
            } else if (upperType.equals("ICE")) {
                if (!value.matches("\\d{15}")) {
                    throw new IllegalArgumentException("ICE invalide. Format attendu: 15 chiffres");
                }
            }
        }
    }

    // ==================== FIXED SUPPLIER DATA ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FixedSupplierDataRequest {
        private String ice;
        private String ifNumber;
        private String rcNumber;
        private String supplier;
        private String address;
        private String phone;
        private String email;
        private String city;
        private String postalCode;
    }

    // ==================== FIELD DEFINITION ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FieldDefinitionRequest {
        @NotBlank(message = "Le nom du champ est requis")
        private String fieldName;

        private List<String> labels;
        private String regexPattern;
        private String fieldType;
        private String detectionMethod;
        private Boolean required;
        private Double confidenceThreshold;
        private String defaultValue;
        private String searchZone;
        private Integer extractionOrder;
        private String description;
    }

    public void validate() {
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("Nom du template requis");
        }

        if (signature == null) {
            throw new IllegalArgumentException("Signature requise");
        }

        signature.validate();
    }
}