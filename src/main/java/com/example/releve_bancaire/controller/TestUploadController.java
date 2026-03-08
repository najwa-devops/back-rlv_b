package com.example.releve_bancaire.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Simple controller for testing invoice upload without authentication.
 * Stores files and metadata in local storage (no database).
 * 
 * For development/testing purposes only.
 */
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class TestUploadController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/upload")
    public ResponseEntity<?> uploadInvoice(
            @RequestParam("file") MultipartFile file) {

        log.info("Test upload: {}", file.getOriginalFilename());

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Fichier vide"));
            }

            String originalName = file.getOriginalFilename();
            if (originalName == null || !isValidFileType(originalName)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Type de fichier non supporte",
                        "supportedTypes", List.of("PDF", "JPG", "JPEG", "PNG")));
            }

            // Create upload directory
            String uploadDir = System.getProperty("user.home") + "/Pictures/Invoices/Invoices/uploads/test";
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Generate unique filename
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String filename = timestamp + "_" + safeName;

            // Save file
            Path filePath = uploadPath.resolve(filename);
            file.transferTo(filePath.toFile());

            // Create metadata file
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("id", UUID.randomUUID().toString());
            metadata.put("originalName", originalName);
            metadata.put("filename", filename);
            metadata.put("filePath", filePath.toString());
            metadata.put("fileSize", file.getSize());
            metadata.put("uploadedAt", LocalDateTime.now().toString());
            metadata.put("status", "UPLOADED");
            metadata.put("note", "Test mode - no OCR processing. For OCR, enable full backend with database.");

            Path metadataPath = uploadPath.resolve(filename + ".json");
            objectMapper.writeValue(metadataPath.toFile(), metadata);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "File uploaded successfully (test mode - no OCR processing)");
            response.put("id", metadata.get("id"));
            response.put("filePath", filePath.toString());
            response.put("filename", originalName);
            response.put("fileSize", file.getSize());
            response.put("uploadedAt", metadata.get("uploadedAt"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Erreur upload test: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/invoices")
    public ResponseEntity<?> listInvoices() {
        try {
            String uploadDir = System.getProperty("user.home") + "/Pictures/Invoices/Invoices/uploads/test";
            Path uploadPath = Paths.get(uploadDir);

            if (!Files.exists(uploadPath)) {
                return ResponseEntity.ok(Map.of(
                        "count", 0,
                        "invoices", Collections.emptyList()));
            }

            List<Map<String, Object>> invoices = new ArrayList<>();

            try (var stream = Files.list(uploadPath)) {
                stream.filter(path -> path.toString().endsWith(".json"))
                        .forEach(metadataPath -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> metadata = objectMapper.readValue(metadataPath.toFile(), Map.class);
                                invoices.add(metadata);
                            } catch (IOException e) {
                                log.warn("Failed to read metadata: {}", metadataPath);
                            }
                        });
            }

            // Sort by uploadedAt descending
            invoices.sort((a, b) -> {
                String aTime = (String) a.get("uploadedAt");
                String bTime = (String) b.get("uploadedAt");
                return bTime.compareTo(aTime);
            });

            return ResponseEntity.ok(Map.of(
                    "count", invoices.size(),
                    "invoices", invoices));

        } catch (IOException e) {
            log.error("Error listing invoices: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/invoices/{id}")
    public ResponseEntity<?> getInvoice(@PathVariable String id) {
        try {
            String uploadDir = System.getProperty("user.home") + "/Pictures/Invoices/Invoices/uploads/test";
            Path uploadPath = Paths.get(uploadDir);

            if (!Files.exists(uploadPath)) {
                return ResponseEntity.notFound().build();
            }

            try (var stream = Files.list(uploadPath).filter(p -> p.toString().endsWith(".json"))) {
                return stream.map(metadataPath -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> metadata = objectMapper.readValue(metadataPath.toFile(), Map.class);
                        if (id.equals(metadata.get("id"))) {
                            return ResponseEntity.ok(metadata);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to read metadata: {}", metadataPath);
                    }
                    return null;
                })
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(ResponseEntity.notFound().build());
            }

        } catch (IOException e) {
            log.error("Error getting invoice: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "Test upload endpoint is ready",
                "mode", "local-storage",
                "ocrProcessing", false));
    }

    private boolean isValidFileType(String filename) {
        return filename.toLowerCase().endsWith(".pdf") ||
                filename.toLowerCase().endsWith(".jpg") ||
                filename.toLowerCase().endsWith(".jpeg") ||
                filename.toLowerCase().endsWith(".png");
    }
}
