package com.example.releve_bancaire.dto.auth;

import lombok.Data;

@Data
public class CreateDossierRequest {
    private Long clientId;
    private String clientUsername;
    private String clientPassword;
    private String clientDisplayName;
    private String dossierName;
    private Long comptableId;
}
