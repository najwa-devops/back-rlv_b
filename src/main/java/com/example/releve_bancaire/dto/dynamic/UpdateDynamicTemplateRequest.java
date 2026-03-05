package com.example.releve_bancaire.dto.dynamic;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateDynamicTemplateRequest {

    private String templateName; // Renommer le template
    private String supplierType; // Changer le type de fournisseur
    private String description; // Mettre à jour la description

    @Valid
    private List<CreateDynamicTemplateRequest.FieldDefinitionRequest> fieldDefinitions;

    @Valid
    private CreateDynamicTemplateRequest.FixedSupplierDataRequest fixedSupplierData;
}
