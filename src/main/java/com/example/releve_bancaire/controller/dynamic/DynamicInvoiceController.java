package com.example.releve_bancaire.controller.dynamic;

import com.example.releve_bancaire.dto.account_tier.TierDto;
import com.example.releve_bancaire.entity.auth.Dossier;
import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.entity.dynamic.DynamicInvoice;
import com.example.releve_bancaire.entity.account_tier.Tier;
import com.example.releve_bancaire.entity.invoice.InvoiceStatus;
import com.example.releve_bancaire.repository.AccountingEntryDao;
import com.example.releve_bancaire.repository.DossierDao;
import com.example.releve_bancaire.repository.DynamicInvoiceDao;
import com.example.releve_bancaire.repository.FieldLearningDataDao;
import com.example.releve_bancaire.servises.FileStorageService;
import com.example.releve_bancaire.servises.account_tier.TierService;
import com.example.releve_bancaire.servises.auth.AuthService;
import com.example.releve_bancaire.servises.auth.SessionKeys;
import com.example.releve_bancaire.servises.auth.SessionUser;
import com.example.releve_bancaire.servises.dynamic.DynamicInvoiceProcessingService;
import com.example.releve_bancaire.security.RequireRole;
import com.example.releve_bancaire.utils.InvoiceTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/dynamic-invoices")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class DynamicInvoiceController {

    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final DynamicInvoiceProcessingService processingService;
    private final FileStorageService fileStorageService;
    private final TierService tierService;
    private final AuthService authService;
    private final DossierDao dossierDao;
    private final AccountingEntryDao accountingEntryDao;
    private final FieldLearningDataDao fieldLearningDataDao;

    @PostMapping("/upload")
    @Transactional
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT })
    public ResponseEntity<?> uploadAndProcess(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("Upload fichier: {}", file.getOriginalFilename());

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        Dossier dossier = requireDossierForUser(sessionUser, resolvedDossierId);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "dossier_forbidden"));
        }

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

            DynamicInvoice processed;
            if (sessionUser.isClient()) {
                processed = createUploadOnlyInvoice(file);
            } else {
                // CORRECTION: Passer le MultipartFile directement
                processed = processingService.processInvoice(file, resolvedDossierId);
            }

            if (dossier != null) {
                processed.setDossier(dossier);
                processed.setDossierId(dossier.getId());
                processed = dynamicInvoiceDao.save(processed);
            }

            return ResponseEntity.ok(toResponse(processed));

        } catch (Exception e) {
            log.error("Erreur upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/upload/batch")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT })
    public ResponseEntity<?> uploadAndProcessBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        int count = files != null ? files.length : 0;
        log.info("Upload batch: {} fichiers", count);

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        Dossier dossier = requireDossierForUser(sessionUser, resolvedDossierId);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "dossier_forbidden"));
        }

        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Aucun fichier fourni"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;

        for (MultipartFile file : files) {
            String originalName = file != null ? file.getOriginalFilename() : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename", originalName != null ? originalName : "unknown");

            try {
                if (file == null || file.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "Fichier vide");
                    results.add(item);
                    continue;
                }

                if (originalName == null || !isValidFileType(originalName)) {
                    item.put("status", "error");
                    item.put("error", "Type de fichier non supporte");
                    item.put("supportedTypes", List.of("PDF", "JPG", "JPEG", "PNG"));
                    results.add(item);
                    continue;
                }

                DynamicInvoice processed = sessionUser.isClient()
                        ? createUploadOnlyInvoice(file)
                        : processingService.processInvoice(file, resolvedDossierId);
                if (dossier != null) {
                    processed.setDossier(dossier);
                    processed.setDossierId(dossier.getId());
                    processed = dynamicInvoiceDao.save(processed);
                }
                item.put("status", "success");
                item.put("invoice", toResponse(processed));
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur upload batch ({}): {}", originalName, e.getMessage(), e);
                item.put("status", "error");
                item.put("error", e.getMessage());
                results.add(item);
            }
        }

        int errorCount = results.size() - successCount;
        return ResponseEntity.ok(Map.of(
                "count", results.size(),
                "successCount", successCount,
                "errorCount", errorCount,
                "results", results));
    }

    @PostMapping("/{id}/process")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> reprocess(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("Retraitement facture: {}", id);
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    try {
                        DynamicInvoice processed = processingService.reprocessExistingInvoice(invoice);
                        return ResponseEntity.ok(toResponse(processed));
                    } catch (Exception e) {
                        log.error("Erreur retraitement: {}", e.getMessage());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT })
    public ResponseEntity<?> getById(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    return ResponseEntity.ok(toDetailedResponse(invoice));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT })
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long templateId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(defaultValue = "50") int limit,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }
        if (requireDossierForUser(sessionUser, dossierId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "dossier_forbidden"));
        }

        List<DynamicInvoice> invoices;
        if (status != null) {
            InvoiceStatus invoiceStatus = InvoiceStatus.valueOf(status.toUpperCase());
            invoices = dynamicInvoiceDao.findByStatusAndDossierIdOrderByCreatedAtDesc(invoiceStatus, dossierId);
        } else if (templateId != null) {
            invoices = dynamicInvoiceDao.findByTemplateIdAndDossierIdOrderByCreatedAtDesc(templateId, dossierId);
        } else {
            invoices = dynamicInvoiceDao.findByDossierIdOrderByCreatedAtDesc(dossierId);
        }

        List<Map<String, Object>> response = invoices.stream()
                .limit(limit)
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", response.size(),
                "invoices", response));
    }

    @DeleteMapping("/{id}")
    @Transactional
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT })
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("Suppression facture: {}", id);
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (sessionUser != null && sessionUser.isClient()
                            && Boolean.TRUE.equals(invoice.getClientValidated())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "client_validated"));
                    }
                    String filePath = invoice.getFilePath();
                    try {
                        accountingEntryDao.deleteByInvoiceId(invoice.getId());
                        fieldLearningDataDao.deleteByInvoiceId(invoice.getId());
                        dynamicInvoiceDao.delete(invoice);
                    } catch (DataIntegrityViolationException ex) {
                        log.error("Suppression facture {} bloquee par contraintes de donnees", id, ex);
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of(
                                        "error", "invoice_delete_conflict",
                                        "message",
                                        "Impossible de supprimer la facture car des donnees liees existent encore."));
                    }

                    if (filePath != null && !filePath.isBlank()) {
                        try {
                            Files.deleteIfExists(Path.of(filePath));
                        } catch (Exception e) {
                            log.warn("Facture {} supprimee en base, mais fichier non supprime: {} ({})",
                                    id, filePath, e.getMessage());
                        }
                    }

                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/fields")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> updateFields(
            @PathVariable Long id,
            @RequestBody Map<String, Object> fields,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("Modification champs facture {}: {}", id, fields.keySet());
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (!invoice.isModifiable()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "Facture validee, modification impossible"));
                    }

                    Map<String, Object> currentFields = invoice.getFieldsData();
                    if (currentFields == null) {
                        currentFields = new LinkedHashMap<>();
                    }
                    currentFields.putAll(fields);
                    invoice.setFieldsData(currentFields);
                    invoice.setIsAvoir(InvoiceTypeDetector.isAvoir(currentFields, invoice.getRawOcrText()));

                    if (invoice.getStatus() == InvoiceStatus.TREATED) {
                        invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);
                    }

                    DynamicInvoice saved = dynamicInvoiceDao.save(invoice);
                    return ResponseEntity.ok(toResponse(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/validate")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> validate(
            @PathVariable Long id,
            @RequestParam(required = false) String userId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        String validator = sessionUser != null ? sessionUser.username() : (userId != null ? userId : "system");
        log.info("Validation facture {} par {}", id, validator);

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (invoice.getStatus() == InvoiceStatus.VALIDATED) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Facture deja validee",
                                "invoice", toResponse(invoice)));
                    }

                    if (!invoice.canBeValidated()) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Facture non prete pour validation",
                                "currentStatus", invoice.getStatus().name(),
                                "requiredStatus", "READY_TO_VALIDATE"));
                    }

                    invoice.validate(validator);
                    DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

                    return ResponseEntity.ok(Map.of(
                            "message", "Facture validee",
                            "invoice", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/client-validate")
    @Transactional
    @RequireRole({ UserRole.CLIENT })
    public ResponseEntity<?> clientValidate(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (Boolean.TRUE.equals(invoice.getClientValidated())) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Facture deja validee par le client",
                                "invoice", toResponse(invoice)));
                    }

                    invoice.setClientValidated(true);
                    invoice.setClientValidatedAt(java.time.LocalDateTime.now());
                    invoice.setClientValidatedBy(sessionUser != null ? sessionUser.username() : "client");
                    DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

                    return ResponseEntity.ok(Map.of(
                            "message", "Facture validee par le client",
                            "invoice", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== BULK ACTIONS ====================

    @PostMapping("/bulk/process")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> bulkProcess(
            @RequestBody Map<String, Object> body,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        List<Long> ids = parseIds(body.get("ids"));
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids_required"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;

        for (Long id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                DynamicInvoice invoice = invoiceOpt.get();
                if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                    item.put("status", "error");
                    item.put("error", "forbidden");
                    results.add(item);
                    continue;
                }
                DynamicInvoice processed = processingService.reprocessExistingInvoice(invoice);
                item.put("status", "success");
                item.put("invoice", toResponse(processed));
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk process ({}): {}", id, e.getMessage(), e);
                item.put("status", "error");
                item.put("error", e.getMessage());
                results.add(item);
            }
        }

        int errorCount = results.size() - successCount;
        return ResponseEntity.ok(Map.of(
                "count", results.size(),
                "successCount", successCount,
                "errorCount", errorCount,
                "results", results));
    }

    @PostMapping("/bulk/validate")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> bulkValidate(
            @RequestBody Map<String, Object> body,
            @RequestParam(required = false) String userId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        List<Long> ids = parseIds(body.get("ids"));
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids_required"));
        }

        String validator = sessionUser.username() != null
                ? sessionUser.username()
                : (userId != null ? userId : "system");

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;

        for (Long id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                DynamicInvoice invoice = invoiceOpt.get();
                if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                    item.put("status", "error");
                    item.put("error", "forbidden");
                    results.add(item);
                    continue;
                }
                if (invoice.getStatus() == InvoiceStatus.VALIDATED) {
                    item.put("status", "skipped");
                    item.put("message", "already_validated");
                    results.add(item);
                    continue;
                }
                if (!invoice.canBeValidated()) {
                    item.put("status", "error");
                    item.put("error", "not_ready");
                    results.add(item);
                    continue;
                }
                invoice.validate(validator);
                DynamicInvoice saved = dynamicInvoiceDao.save(invoice);
                item.put("status", "success");
                item.put("invoice", toResponse(saved));
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk validate ({}): {}", id, e.getMessage(), e);
                item.put("status", "error");
                item.put("error", e.getMessage());
                results.add(item);
            }
        }

        int errorCount = results.size() - successCount;
        return ResponseEntity.ok(Map.of(
                "count", results.size(),
                "successCount", successCount,
                "errorCount", errorCount,
                "results", results));
    }

    @PostMapping("/bulk/delete")
    @Transactional
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> bulkDelete(
            @RequestBody Map<String, Object> body,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        List<Long> ids = parseIds(body.get("ids"));
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids_required"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;

        for (Long id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                DynamicInvoice invoice = invoiceOpt.get();
                if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                    item.put("status", "error");
                    item.put("error", "forbidden");
                    results.add(item);
                    continue;
                }
                String filePath = invoice.getFilePath();
                if (filePath == null || filePath.isBlank()) {
                    item.put("status", "error");
                    item.put("error", "file_missing");
                    results.add(item);
                    continue;
                }

                dynamicInvoiceDao.delete(invoice);

                try {
                    Files.deleteIfExists(Path.of(filePath));
                } catch (Exception e) {
                    log.warn("Facture {} supprimee en base, mais fichier non supprime: {} ({})",
                            id, filePath, e.getMessage());
                }

                item.put("status", "success");
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk delete ({}): {}", id, e.getMessage(), e);
                item.put("status", "error");
                item.put("error", e.getMessage());
                results.add(item);
            }
        }

        int errorCount = results.size() - successCount;
        return ResponseEntity.ok(Map.of(
                "count", results.size(),
                "successCount", successCount,
                "errorCount", errorCount,
                "results", results));
    }

    // ==================== NOUVEAU: GET SIGNATURES DISPONIBLES ====================

    /**
     * Récupère les signatures disponibles pour créer un template
     * GET /api/dynamic-invoices/{id}/available-signatures
     */
    @GetMapping("/{id}/available-signatures")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT })
    public ResponseEntity<?> getAvailableSignatures(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("GET /api/dynamic-invoices/{}/available-signatures", id);

        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    List<Map<String, Object>> signatures = new ArrayList<>();

                    Map<String, Object> fieldsData = invoice.getFieldsData();
                    if (fieldsData == null) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Aucune donnée extraite",
                                "signatures", signatures));
                    }

                    String ifNumber = getStringValue(fieldsData, "ifNumber");
                    String ice = getStringValue(fieldsData, "ice");
                    String rcNumber = getStringValue(fieldsData, "rcNumber");
                    String supplier = getStringValue(fieldsData, "supplier");

                    // Signature IF (prioritaire)
                    if (ifNumber != null && !ifNumber.isBlank()) {
                        signatures.add(Map.of(
                                "type", "IF",
                                "value", ifNumber,
                                "label", "IF: " + ifNumber,
                                "recommended", true,
                                "reason", "Identifiant unique du fournisseur (recommandé)"));
                    }

                    // Signature ICE
                    if (ice != null && !ice.isBlank()) {
                        signatures.add(Map.of(
                                "type", "ICE",
                                "value", ice,
                                "label", "ICE: " + ice,
                                "recommended", false,
                                "reason", "Peut apparaître plusieurs fois (client + fournisseur)"));
                    }

                    // Signature RC
                    if (rcNumber != null && !rcNumber.isBlank()) {
                        signatures.add(Map.of(
                                "type", "RC",
                                "value", rcNumber,
                                "label", "RC: " + rcNumber,
                                "recommended", false,
                                "reason", "Moins standardisé"));
                    }

                    if (signatures.isEmpty()) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Aucune signature détectée",
                                "suggestion", "Vérifiez que l'OCR a extrait les données correctement",
                                "signatures", signatures));
                    }

                    return ResponseEntity.ok(Map.of(
                            "invoiceId", id,
                            "supplier", supplier != null ? supplier : "Non détecté",
                            "signatures", signatures,
                            "recommendedSignature", signatures.get(0)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT })
    public ResponseEntity<?> stats(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        Map<String, Object> stats = new LinkedHashMap<>();

        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        if (requireDossierForUser(sessionUser, dossierId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "dossier_forbidden"));
        }

        boolean clientValidatedOnly = sessionUser.isClient();

        if (clientValidatedOnly) {
            stats.put("total", dynamicInvoiceDao.countByDossierIdAndClientValidatedTrue(dossierId));
            stats.put("pending",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.PENDING,
                            dossierId));
            stats.put("processing",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.PROCESSING,
                            dossierId));
            stats.put("treated",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.TREATED,
                            dossierId));
            stats.put("readyToValidate",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.READY_TO_VALIDATE,
                            dossierId));
            stats.put("validated",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.VALIDATED,
                            dossierId));
            stats.put("error",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.ERROR, dossierId));
        } else {
            stats.put("total", dynamicInvoiceDao.countByDossierId(dossierId));
            stats.put("pending", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.PENDING, dossierId));
            stats.put("processing", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.PROCESSING, dossierId));
            stats.put("treated", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.TREATED, dossierId));
            stats.put("readyToValidate",
                    dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.READY_TO_VALIDATE, dossierId));
            stats.put("validated", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.VALIDATED, dossierId));
            stats.put("error", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.ERROR, dossierId));
        }

        List<DynamicInvoice> lowConfidence = clientValidatedOnly
                ? dynamicInvoiceDao.findLowConfidenceByDossierIdClientValidated(0.7, dossierId)
                : dynamicInvoiceDao.findLowConfidenceByDossierId(0.7, dossierId);
        stats.put("lowConfidenceCount", lowConfidence.size());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/files/{filename}")
    @CrossOrigin("*")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT })
    public ResponseEntity<?> getFile(
            @PathVariable String filename,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.debug("Requ?te de fichier re?ue pour: {}", filename);
        SessionUser sessionUser = authService.requireSessionUser(session);

        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (invoiceId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(invoiceId);
        if (invoiceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DynamicInvoice invoice = invoiceOpt.get();
        if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String filePath = invoice.getFilePath();
        if (filePath != null && !filePath.endsWith(filename)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Path dir = fileStorageService.getBaseDirForFiles();
            log.debug("Recherche dans le r?pertoire de base: {}", dir);

            if (!Files.exists(dir)) {
                log.error("Le r?pertoire de base n'existe pas: {}", dir);
                return ResponseEntity.notFound().build();
            }

            // Chercher fichier se terminant par filename (car pr?fixe UUID ajout?)
            // Utilisation de try-with-resources pour fermer le stream
            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                Path foundPath = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(filename))
                        .findFirst()
                        .orElse(null);

                if (foundPath == null) {
                    log.warn("Fichier non trouv? apr?s recherche r?cursive: {}", filename);
                    return ResponseEntity.notFound().build();
                }

                log.debug("Fichier trouv?: {}", foundPath);
                Resource resource = new UrlResource(foundPath.toUri());

                if (!resource.exists() || !resource.isReadable()) {
                    log.error("Fichier trouv? mais non lisible: {}", foundPath);
                    return ResponseEntity.notFound().build();
                }

                String contentType = Files.probeContentType(foundPath);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                        .body(resource);
            }
        } catch (Exception e) {
            log.error("Erreur lors de la r?cup?ration du fichier {}: {}", filename, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{id}/link-tier")
    @Transactional
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> linkTier(
            @PathVariable Long id,
            @RequestBody Map<String, Long> request,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        Long tierId = request.get("tierId");

        log.info("=== LIAISON TIER À FACTURE ===");
        log.info("Invoice ID: {}, Tier ID: {}", id, tierId);

        try {
            // 1. Récupérer la facture
            DynamicInvoice invoice = dynamicInvoiceDao.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Facture non trouvée: " + id));

            SessionUser sessionUser = authService.requireSessionUser(session);

            dossierId = resolveDossierId(sessionUser, dossierId, session);
            if (dossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }
            if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "forbidden"));
            }

            // 2. Récupérer le tier
            Optional<TierDto> tierDtoOpt = tierService.getTierById(tierId, dossierId);

            if (tierDtoOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "success", false,
                        "error", "Tier non trouvé: " + tierId));
            }

            TierDto tierDto = tierDtoOpt.get();

            log.info("Liaison: Facture '{}' → Tier '{}'",
                    invoice.getFilename(), tierDto.getLibelle());

            // 3. Convertir DTO en Entity
            Tier tier = convertDtoToEntity(tierDto);

            // 4. Lier le tier à la facture
            invoice.setTier(tier);
            invoice.setTierId(tier.getId());
            invoice.setTierName(tier.getLibelle());

            // MODIFICATION : Initialiser fieldsData ICI
            Map<String, Object> fieldsData = invoice.getFieldsData();
            if (fieldsData == null) {
                fieldsData = new LinkedHashMap<>();
            }

            List<String> autoFilledFields = new ArrayList<>();

            // NOUVEAU : Remplacer supplier par Tier.libelle
            fieldsData.put("supplier", tier.getLibelle());
            autoFilledFields.add("supplier");
            log.info("Supplier remplacé par Tier.libelle: {}", tier.getLibelle());

            // 5. Auto-remplir les comptes comptables si disponibles
            if (tier.hasAccountingConfiguration()) {
                fieldsData.put("tierNumber", tier.getTierNumber());
                autoFilledFields.add("tierNumber");

                if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                    fieldsData.put("collectifAccount", tier.getCollectifAccount());
                    autoFilledFields.add("collectifAccount");
                }

                fieldsData.put("chargeAccount", tier.getDefaultChargeAccount());
                fieldsData.put("tvaAccount", tier.getTvaAccount());
                fieldsData.put("tvaRate", tier.getDefaultTvaRate());

                autoFilledFields.add("chargeAccount");
                autoFilledFields.add("tvaAccount");
                autoFilledFields.add("tvaRate");

                log.info("Comptes comptables auto-remplis:");
                log.info("  - tierNumber: {}", tier.getTierNumber());
                log.info("  - chargeAccount: {}", tier.getDefaultChargeAccount());
                log.info("  - tvaAccount: {}", tier.getTvaAccount());
            }

            invoice.setFieldsData(fieldsData);

            // Mettre à jour autoFilledFields
            List<String> currentAutoFilled = invoice.getAutoFilledFields();
            if (currentAutoFilled == null) {
                currentAutoFilled = new ArrayList<>();
            }
            currentAutoFilled.addAll(autoFilledFields);
            invoice.setAutoFilledFields(currentAutoFilled);

            // 6. Mettre à jour le status si maintenant complet
            if (invoice.getStatus() == InvoiceStatus.TREATED) {
                invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);
                log.info("Status mis à jour: READY_TO_VALIDATE");
            }

            // 7. Sauvegarder
            DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

            log.info("Tier lié avec succès");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Fournisseur lié avec succès",
                    "invoice", toDetailedResponse(saved)));

        } catch (IllegalArgumentException e) {
            log.error("Erreur validation: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur inattendue: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Erreur serveur: " + e.getMessage()));
        }
    }

    // ===================== HELPERS =====================

    private DynamicInvoice createUploadOnlyInvoice(MultipartFile file) {
        String filePath = fileStorageService.store(file);
        DynamicInvoice invoice = new DynamicInvoice();

        String originalName = file.getOriginalFilename();
        String safeName = originalName != null ? originalName : "upload";

        invoice.setFilename(safeName);
        invoice.setOriginalName(safeName);
        invoice.setFilePath(filePath);
        invoice.setFileSize(file.getSize());
        invoice.setRawOcrText("");
        invoice.setExtractedData(new LinkedHashMap<>());
        invoice.setFieldsData(new LinkedHashMap<>());
        invoice.setMissingFields(new ArrayList<>());
        invoice.setLowConfidenceFields(new ArrayList<>());
        invoice.setAutoFilledFields(new ArrayList<>());
        invoice.setStatus(InvoiceStatus.PENDING);
        return invoice;
    }

    private Dossier requireDossierForUser(SessionUser sessionUser, Long dossierId) {
        if (sessionUser == null || dossierId == null) {
            return null;
        }

        if (sessionUser.isAdmin()) {
            return dossierDao.findById(dossierId).orElse(null);
        }
        if (sessionUser.isComptable()) {
            return dossierDao.findByIdAndComptableId(dossierId, sessionUser.id()).orElse(null);
        }
        if (sessionUser.isClient()) {
            return dossierDao.findByIdAndClientId(dossierId, sessionUser.id()).orElse(null);
        }
        return null;
    }

    private Long resolveDossierId(SessionUser sessionUser, Long dossierId, HttpSession session) {
        if (dossierId != null) {
            return dossierId;
        }
        if (sessionUser == null || session == null) {
            return null;
        }
        Object rawId = session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
        if (rawId == null) {
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return fallback.getId();
                }
            }
            return null;
        }
        Long resolvedId;
        try {
            resolvedId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException ex) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return fallback.getId();
                }
            }
            return null;
        }
        if (requireDossierForUser(sessionUser, resolvedId) == null) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return fallback.getId();
                }
            }
            return null;
        }
        return resolvedId;
    }

    private boolean canAccessInvoiceInDossier(SessionUser sessionUser, DynamicInvoice invoice, Long dossierId) {
        if (sessionUser == null || invoice == null || dossierId == null) {
            return false;
        }
        Long invoiceDossierId = invoice.getDossierId();
        if (invoiceDossierId == null || !dossierId.equals(invoiceDossierId)) {
            return false;
        }
        return canAccessInvoice(sessionUser, invoice);
    }

    private boolean canAccessInvoice(SessionUser sessionUser, DynamicInvoice invoice) {
        if (sessionUser == null || invoice == null) {
            return false;
        }
        if (sessionUser.isAdmin()) {
            return true;
        }
        Long dossierId = invoice.getDossierId();
        if (dossierId == null) {
            return false;
        }
        if (sessionUser.isComptable()) {
            return dossierDao.findByIdAndComptableId(dossierId, sessionUser.id()).isPresent();
        }
        if (sessionUser.isClient()) {
            return dossierDao.findByIdAndClientId(dossierId, sessionUser.id()).isPresent();
        }
        return false;
    }

    private boolean isValidFileType(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private List<Long> parseIds(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item == null) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(item.toString()));
                } catch (NumberFormatException ignored) {
                }
            }
            return ids;
        }
        if (raw instanceof String str) {
            String[] parts = str.split(",");
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(part.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ids;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> toResponse(DynamicInvoice invoice) {
        Map<String, Object> fieldsData = invoice.getFieldsData() != null
                ? invoice.getFieldsData()
                : new LinkedHashMap<>();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", invoice.getId());
        response.put("filename", invoice.getFilename());
        response.put("originalName", invoice.getOriginalName());
        response.put("status", invoice.getStatus().name());
        response.put("templateId", invoice.getTemplateId());
        response.put("templateName", invoice.getTemplateName());
        response.put("overallConfidence", invoice.getOverallConfidence());
        response.put("dossierId", invoice.getDossierId());
        response.put("clientValidated", Boolean.TRUE.equals(invoice.getClientValidated()));
        response.put("clientValidatedAt", invoice.getClientValidatedAt());
        response.put("clientValidatedBy", invoice.getClientValidatedBy());
        response.put("accounted", Boolean.TRUE.equals(invoice.getAccounted()));
        response.put("accountedAt", invoice.getAccountedAt());
        response.put("accountedBy", invoice.getAccountedBy());
        response.put("isAvoir", Boolean.TRUE.equals(invoice.getIsAvoir()));
        response.put("fieldsData", fieldsData);
        response.put("invoiceDate", firstNonBlank(
                getStringValue(fieldsData, "invoiceDate"),
                getStringValue(fieldsData, "dateFacture"),
                getStringValue(fieldsData, "date")));
        response.put("missingFields", invoice.getMissingFields());
        response.put("lowConfidenceFields", invoice.getLowConfidenceFields());
        response.put("autoFilledFields", invoice.getAutoFilledFields() != null
                ? invoice.getAutoFilledFields()
                : List.of());
        response.put("rawOcrText", invoice.getRawOcrText());
        response.put("createdAt", invoice.getCreatedAt());
        response.put("updatedAt", invoice.getUpdatedAt());
        return response;
    }

    private Map<String, Object> toDetailedResponse(DynamicInvoice invoice) {
        Map<String, Object> response = toResponse(invoice);
        response.put("extractedData", invoice.getExtractedData());
        response.put("rawOcrText", invoice.getRawOcrText());
        response.put("filePath", invoice.getFilePath());
        response.put("fileSize", invoice.getFileSize());
        response.put("clientValidated", Boolean.TRUE.equals(invoice.getClientValidated()));
        response.put("clientValidatedAt", invoice.getClientValidatedAt());
        response.put("clientValidatedBy", invoice.getClientValidatedBy());
        response.put("validatedAt", invoice.getValidatedAt());
        response.put("validatedBy", invoice.getValidatedBy());
        response.put("accounted", Boolean.TRUE.equals(invoice.getAccounted()));
        response.put("accountedAt", invoice.getAccountedAt());
        response.put("accountedBy", invoice.getAccountedBy());
        response.put("isAvoir", Boolean.TRUE.equals(invoice.getIsAvoir()));

        if (invoice.getDetectedSignature() != null) {
            response.put("detectedSignature", Map.of(
                    "type", invoice.getDetectedSignature().getSignatureType().name(),
                    "value", invoice.getDetectedSignature().getSignatureValue()));
        }

        Long tierId = invoice.getTierId();
        if (tierId != null) {
            Optional<TierDto> tierDtoOpt = tierService.getTierById(tierId, invoice.getDossierId());
            if (tierDtoOpt.isPresent()) {
                TierDto tier = tierDtoOpt.get();

                Map<String, Object> tierData = new LinkedHashMap<>();
                tierData.put("id", tier.getId());
                tierData.put("libelle", tier.getLibelle());
                tierData.put("auxiliaireMode", tier.getAuxiliaireMode());
                tierData.put("tierNumber", tier.getTierNumber() != null ? tier.getTierNumber() : "");
                tierData.put("collectifAccount", tier.getCollectifAccount() != null ? tier.getCollectifAccount() : "");
                tierData.put("displayAccount", tier.getDisplayAccount());
                tierData.put("ifNumber", tier.getIfNumber() != null ? tier.getIfNumber() : "");
                tierData.put("ice", tier.getIce() != null ? tier.getIce() : "");
                tierData.put("rcNumber", tier.getRcNumber() != null ? tier.getRcNumber() : "");
                tierData.put("defaultChargeAccount",
                        tier.getDefaultChargeAccount() != null ? tier.getDefaultChargeAccount() : "");
                tierData.put("tvaAccount", tier.getTvaAccount() != null ? tier.getTvaAccount() : "");
                tierData.put("defaultTvaRate", tier.getDefaultTvaRate() != null ? tier.getDefaultTvaRate() : 0.0);
                tierData.put("active", tier.getActive());

                boolean hasAccountingConfig = tier.getDefaultChargeAccount() != null
                        && !tier.getDefaultChargeAccount().isBlank()
                        && tier.getTvaAccount() != null
                        && !tier.getTvaAccount().isBlank();
                tierData.put("hasAccountingConfig", hasAccountingConfig);

                response.put("tier", tierData);

                if (hasAccountingConfig) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fieldsData = (Map<String, Object>) response.get("fieldsData");
                    if (fieldsData != null) {
                        fieldsData.put("tierNumber", tier.getTierNumber());
                        if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                            fieldsData.put("collectifAccount", tier.getCollectifAccount());
                        }
                        fieldsData.put("chargeAccount", tier.getDefaultChargeAccount());
                        fieldsData.put("tvaAccount", tier.getTvaAccount());
                        fieldsData.put("tvaRate", tier.getDefaultTvaRate());
                    }
                }
            } else {
                response.put("tier", Map.of(
                        "id", tierId,
                        "libelle", invoice.getTierName() != null ? invoice.getTierName() : "",
                        "active", false,
                        "hasAccountingConfig", false));
            }
        } else {
            response.put("tier", null);
        }

        return response;
    }

    private Tier convertDtoToEntity(com.example.releve_bancaire.dto.account_tier.TierDto dto) {
        return Tier.builder()
                .id(dto.getId())
                .auxiliaireMode(dto.getAuxiliaireMode())
                .tierNumber(dto.getTierNumber())
                .collectifAccount(dto.getCollectifAccount())
                .libelle(dto.getLibelle())
                .ifNumber(dto.getIfNumber())
                .ice(dto.getIce())
                .rcNumber(dto.getRcNumber())
                .defaultChargeAccount(dto.getDefaultChargeAccount())
                .tvaAccount(dto.getTvaAccount())
                .defaultTvaRate(dto.getDefaultTvaRate())
                .active(dto.getActive())
                .build();
    }
}
