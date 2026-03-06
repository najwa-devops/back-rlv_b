package com.example.releve_bancaire.banking_services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class BankFileStorageService {

    @Value("${banking.upload.dir:uploads/banking}")
    private String bankingUploadDir;

    public StoredBankFile storeBankStatement(MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "statement";
            String sanitizedOriginalName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String generatedFilename = UUID.randomUUID() + "_" + sanitizedOriginalName;
            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
            Path uploadDir = Paths.get(bankingUploadDir).toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);
            Path storedPath = uploadDir.resolve(generatedFilename);
            file.transferTo(storedPath.toFile());

            return new StoredBankFile(
                    generatedFilename,
                    originalName,
                    contentType,
                    file.getSize(),
                    storedPath.toString());
        } catch (Exception e) {
            throw new RuntimeException("Erreur stockage fichier sur disque", e);
        }
    }

    public Resource loadBankStatement(String filename) {
        try {
            String safeFilename = filename != null ? Paths.get(filename).getFileName().toString() : "";
            if (safeFilename.isBlank()) {
                return null;
            }
            Path filePath = Paths.get(bankingUploadDir).toAbsolutePath().normalize().resolve(safeFilename).normalize();
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return null;
            }
            return new UrlResource(filePath.toUri());
        } catch (Exception e) {
            return null;
        }
    }

    public List<StoredFileInfo> listStoredBankStatements() {
        try {
            Path uploadDir = Paths.get(bankingUploadDir).toAbsolutePath().normalize();
            if (!Files.exists(uploadDir)) {
                return List.of();
            }
            try (var stream = Files.list(uploadDir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(path -> {
                            try {
                                return new StoredFileInfo(
                                        path.getFileName().toString(),
                                        path.toString(),
                                        Files.size(path),
                                        Files.getLastModifiedTime(path).toMillis());
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparingLong(StoredFileInfo::lastModified).reversed())
                        .toList();
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    public record StoredBankFile(
            String filename,
            String originalName,
            String contentType,
            long size,
            String filePath) {
    }

    public record StoredFileInfo(
            String filename,
            String filePath,
            long size,
            long lastModified) {
    }
}
