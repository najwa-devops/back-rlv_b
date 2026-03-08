package com.example.releve_bancaire.dto.settings;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpsertDossierGeneralParamsRequest {
    private String companyName;
    private String address;
    private String legalForm;
    private String rcNumber;
    private String ifNumber;
    private String tsc;
    private String activity;
    private String category;
    private String professionalTax;
    private String ice;
    private String cniOrResidenceCard;
    private String legalRepresentative;
    private BigDecimal cmRate;
    private BigDecimal isRate;
    private BigDecimal capital;
    private Boolean subjectToRas;
    private Boolean individualPerson;
    private Boolean hasFiscalRegularityCertificate;
}
