package com.example.releve_bancaire.releve_bancaire.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * DTO pour l'upload d'un relevé bancaire
 */
@Data
public class BankStatementUploadRequest {
    private MultipartFile file;
    private String rib; // Optionnel, peut être extrait automatiquement
    private Integer month; // Optionnel
    private Integer year; // Optionnel
}
