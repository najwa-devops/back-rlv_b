package com.example.releve_bancaire.dto.dynamic;

import com.example.releve_bancaire.entity.dynamic.DynamicTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO pour DynamicTemplate (réponse API)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamicTemplateDto {

    private Long id;
    private String templateName;
    private String supplierType;
    private SignatureDto signature;
    private List<FieldDefinitionDto> fieldDefinitions;
    private FixedSupplierDataDto fixedSupplierData;
    private Boolean active;
    private Integer version;
    private String description;

    // Métriques
    private Integer usageCount;
    private Integer successCount;
    private Double successRate;
    private Boolean reliable;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private String createdBy;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SignatureDto {
        private String type; // ICE, IF, SUPPLIER
        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FieldDefinitionDto {
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FixedSupplierDataDto {
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

    /**
     * Convertit une entité en DTO
     */
    public static DynamicTemplateDto fromEntity(DynamicTemplate entity) {
        if (entity == null)
            return null;

        DynamicTemplateDtoBuilder builder = DynamicTemplateDto.builder()
                .id(entity.getId())
                .templateName(entity.getTemplateName())
                .supplierType(entity.getSupplierType())
                .active(entity.getActive())
                .version(entity.getVersion())
                .description(entity.getDescription())
                .usageCount(entity.getUsageCount())
                .successCount(entity.getSuccessCount())
                .successRate(entity.getSuccessRate())
                .reliable(entity.isReliable())
                .lastUsedAt(entity.getLastUsedAt())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy());

        // Signature
        if (entity.getSignature() != null) {
            builder.signature(SignatureDto.builder()
                    .type(entity.getSignature().getSignatureType().name())
                    .value(entity.getSignature().getSignatureValue())
                    .build());
        }

        // Field definitions
        if (entity.getFieldDefinitions() != null) {
            builder.fieldDefinitions(entity.getFieldDefinitions().stream()
                    .map(f -> FieldDefinitionDto.builder()
                            .fieldName(f.getFieldName())
                            .labels(f.getLabels())
                            .regexPattern(f.getRegexPattern())
                            .fieldType(f.getFieldType())
                            .detectionMethod(f.getDetectionMethod())
                            .required(f.getRequired())
                            .confidenceThreshold(f.getConfidenceThreshold())
                            .defaultValue(f.getDefaultValue())
                            .searchZone(f.getSearchZone())
                            .extractionOrder(f.getExtractionOrder())
                            .description(f.getDescription())
                            .build())
                    .toList());
        }

        // Fixed supplier data
        if (entity.getFixedSupplierData() != null) {
            DynamicTemplate.FixedSupplierData fsd = entity.getFixedSupplierData();
            builder.fixedSupplierData(FixedSupplierDataDto.builder()
                    .ice(fsd.getIce())
                    .ifNumber(fsd.getIfNumber())
                    .rcNumber(fsd.getRcNumber())
                    .supplier(fsd.getSupplier())
                    .address(fsd.getAddress())
                    .phone(fsd.getPhone())
                    .email(fsd.getEmail())
                    .city(fsd.getCity())
                    .postalCode(fsd.getPostalCode())
                    .build());
        }

        return builder.build();
    }
}
