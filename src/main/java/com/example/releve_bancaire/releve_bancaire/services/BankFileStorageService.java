package com.example.releve_bancaire.releve_bancaire.services;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
public class BankFileStorageService {

    public StoredBankFile storeBankStatement(MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "statement";
            String sanitizedOriginalName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String generatedFilename = UUID.randomUUID() + "_" + sanitizedOriginalName;
            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

            return new StoredBankFile(
                    generatedFilename,
                    originalName,
                    contentType,
                    file.getSize(),
                    file.getBytes());
        } catch (Exception e) {
            throw new RuntimeException("Erreur stockage fichier en base", e);
        }
    }

    public record StoredBankFile(
            String filename,
            String originalName,
            String contentType,
            long size,
            byte[] data) {
    }
}
