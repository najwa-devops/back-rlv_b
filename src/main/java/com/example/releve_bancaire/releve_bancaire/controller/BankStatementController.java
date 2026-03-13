package com.example.releve_bancaire.releve_bancaire.controller;

import com.example.releve_bancaire.accounting_services.ComptabilisationWorkflowService;
import com.example.releve_bancaire.accounting_repository.AccountingEntryRepository;
import com.example.releve_bancaire.releve_bancaire.entity.BankStatement;
import com.example.releve_bancaire.releve_bancaire.entity.BankStatus;
import com.example.releve_bancaire.releve_bancaire.entity.BankTransaction;
import com.example.releve_bancaire.releve_bancaire.repository.BankStatementRepository;
import com.example.releve_bancaire.releve_bancaire.repository.BankTransactionRepository;
import com.example.releve_bancaire.releve_bancaire.services.BankFileStorageService;
import com.example.releve_bancaire.releve_bancaire.services.BankAliasResolver;
import com.example.releve_bancaire.releve_bancaire.services.BankStatementProcessingService;
import com.example.releve_bancaire.releve_bancaire.services.BankTransactionAccountLearningService;
import com.example.releve_bancaire.releve_bancaire.services.BankStatementValidatorService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ✅ API REST RELEVÉS BANCAIRES - VERSION
 * 
 * Utilise BankStatementProcessingService pour une extraction améliorée
 */
@RestController
@RequestMapping({ "/api/v2/bank-statements", "/api/bank-statements" })
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class BankStatementController {

    private static final String DEFAULT_COMPTE = "349700000";

    private final BankStatementRepository repository;
    private final BankTransactionRepository transactionRepository;
    private final AccountingEntryRepository accountingEntryRepository;
    private final BankStatementProcessingService processingService;
    private final BankStatementValidatorService validatorService;
    private final ComptabilisationWorkflowService comptabilisationWorkflowService;
    private final BankTransactionAccountLearningService accountLearningService;
    private final BankFileStorageService bankFileStorageService;
    private static final Pattern DUPLICATE_OF_PATTERN = Pattern.compile("DUPLIQUE_OF:(\\d+)");
    private static final Pattern OCR_DATE_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2}(?:\\s*[\\/\\-.]\\s*|\\s+)\\d{1,2}(?:(?:\\s*[\\/\\-.]\\s*|\\s+)\\d{2,4})?)(?!\\d)");
    private static final DateTimeFormatter STATEMENT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yy");

    // ==================== UPLOAD & TRAITEMENT ====================

    @PostMapping("/upload")
    public ResponseEntity<?> uploadAndProcess(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "bankType", required = false) String bankType,
            @RequestParam(name = "allowedBanks", required = false) List<String> allowedBanks) {
        log.info("📤 Upload relevé: {} (BankType={}, AllowedBanks={})",
                file.getOriginalFilename(), bankType, allowedBanks);

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Fichier vide"));
            }

            String originalName = file.getOriginalFilename();
            String contentType = file.getContentType();

            log.info("Type MIME: {}, Nom: {}", contentType, originalName);

            // Validation
            boolean validMime = isValidMimeType(contentType);
            boolean validExt = isValidExtension(originalName);

            if (!validMime && !validExt) {
                log.warn("Upload rejeté - MIME: {}, Ext: {}", contentType, originalName);
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Type de fichier non supporté",
                        "receivedType", contentType != null ? contentType : "unknown",
                        "receivedName", originalName != null ? originalName : "unknown",
                        "supportedTypes", List.of(
                                "image/png", "image/jpeg", "application/pdf",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
            }

            // Stocker le fichier
            BankFileStorageService.StoredBankFile storedFile = bankFileStorageService.storeBankStatement(file);
            log.info("✅ Fichier stocké en base: {}", storedFile.filename());

            // Créer l'entité
            BankStatement statement = new BankStatement();
            statement.setFilename(storedFile.filename());
            statement.setOriginalName(storedFile.originalName());
            statement.setFilePath("DB_ONLY");
            statement.setFileSize(storedFile.size());
            statement.setFileContentType(storedFile.contentType());
            statement.setFileData(storedFile.data());
            statement.setStatus(BankStatus.PENDING);

            // Les dates de période sont déterminées par OCR/extraction metadata.
            statement.setMonth(null);
            statement.setYear(null);

            BankStatement saved = repository.save(statement);
            log.info("✅ Relevé créé: ID={}", saved.getId());

            List<String> cleanedAllowedBanks = normalizeAllowedBanks(allowedBanks);

            // Traiter de manière asynchrone avec
            log.info("🚀 Lancement traitement asynchrone (Banque: {}, Autorisées: {})", bankType,
                    cleanedAllowedBanks);
            processingService.processStatementAsync(saved.getId(), bankType, cleanedAllowedBanks);

            return ResponseEntity.accepted().body(toResponse(saved));

        } catch (Exception e) {
            log.error("❌ Erreur upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

    @PostMapping({ "/{id}/process", "/{id}/retry-failed" })
    public ResponseEntity<?> reprocess(
            @PathVariable("id") Long id,
            @RequestParam(name = "allowedBanks", required = false) List<String> allowedBanks) {
        log.info("🔌 Reprocess request received. Raw allowedBanks param: {}", allowedBanks);
        List<String> cleanedAllowedBanks = normalizeAllowedBanks(allowedBanks);
        log.info("🔄 Retraitement relevé: {} (Final cleaned allowedBanks: {})", id, cleanedAllowedBanks);

        try {
            processingService.reprocessStatementAsync(id, cleanedAllowedBanks);
            return ResponseEntity.accepted().body(Map.of(
                    "message", "Reprise démarrée",
                    "allowedBanks", cleanedAllowedBanks));
        } catch (Exception e) {
            log.error("❌ Erreur retraitement: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== CRUD ====================

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getById(@PathVariable("id") Long id) {
        log.info("📖 Récupération relevé: {}", id);

        return repository.findById(id)
                .map(statement -> {
                    log.info("✅ Relevé trouvé: {} transactions", statement.getTransactionCount());
                    return ResponseEntity.ok(toDetailedResponse(statement));
                })
                .orElseGet(() -> {
                    log.warn("⚠️ Relevé {} non trouvé", id);
                    return ResponseEntity.notFound().build();
                });
    }

    @GetMapping("/{id}/final")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getFinalById(@PathVariable("id") Long id) {
        return repository.findById(id)
                .map(statement -> ResponseEntity.ok(buildFinalResponse(statement, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/ocr-text")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getOcrText(@PathVariable("id") Long id) {
        return repository.findById(id)
                .map(statement -> ResponseEntity.ok(Map.of(
                        "id", id,
                        "filename", statement.getFilename(),
                        "rawOcrText", statement.getRawOcrText() != null ? statement.getRawOcrText() : "",
                        "cleanedOcrText", statement.getCleanedOcrText() != null ? statement.getCleanedOcrText() : "")))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "rib", required = false) String rib,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "limit", defaultValue = "1000") int limit) {

        try {
            List<BankStatement> statements;

            if (status != null) {
                BankStatus bankStatus = BankStatus.fromExternalValue(status);
                if (bankStatus == null) {
                    return ResponseEntity.badRequest().body(
                            Map.of("error", "Statut invalide: " + status));
                }
                statements = repository.findByStatusOrderByCreatedAtDesc(bankStatus);
            } else if (rib != null) {
                statements = repository.findByRibOrderByYearDescMonthDesc(rib);
            } else if (year != null && month != null) {
                statements = repository.findByYearAndMonthOrderByRib(year, month);
            } else if (year != null) {
                statements = repository.findByYearOrderByMonthDesc(year);
            } else {
                statements = repository.findAllOrderByCreatedAtDesc();
            }

            List<Map<String, Object>> mappedStatements = statements.stream()
                    .limit(limit)
                    .map(this::toResponse)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "count", mappedStatements.size(),
                    "statements", mappedStatements));

        } catch (Exception e) {
            log.error("❌ Erreur listage: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "Erreur lors du listage: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable("id") Long id) {
        Optional<BankStatement> statementOpt = repository.findById(id);

        if (statementOpt.isEmpty()) {
            log.info("Relevé {} déjà supprimé", id);
            return ResponseEntity.noContent().build();
        }

        processingService.deleteStatement(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/all")
    @Transactional
    public ResponseEntity<?> deleteAll() {
        log.info("🗑️ Suppression de TOUS les relevés bancaires");
        transactionRepository.deleteAllInBatch();
        repository.deleteAllInBatch();
        return ResponseEntity.noContent().build();
    }

    // ==================== VALIDATION ====================

    @PostMapping("/{id}/validate")
    public ResponseEntity<?> validate(
            @PathVariable("id") Long id,
            @RequestParam(name = "userId", defaultValue = "system") String userId) {
        log.info("✅ Validation relevé {} par {}", id, userId);

        return repository.findById(id)
                .map(statement -> {
                    if (statement.getStatus() == BankStatus.VALIDATED) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Relevé déjà validé",
                                "statement", toResponse(statement)));
                    }

                    if (!statement.canBeValidated()) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Relevé non prêt pour validation",
                                "currentStatus", statement.getStatus().name(),
                                "requiredStatus", "READY_TO_VALIDATE ou TREATED"));
                    }

                    statement.validate(userId);
                    BankStatement saved = repository.save(statement);

                    return ResponseEntity.ok(Map.of(
                            "message", "Relevé validé",
                            "statement", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/validate-full")
    public ResponseEntity<?> validateFully(@PathVariable("id") Long id) {
        return repository.findById(id)
                .map(statement -> {
                    var result = validatorService.validateFully(statement);
                    repository.save(statement);

                    return ResponseEntity.ok(Map.of(
                            "statementId", result.statementId,
                            "isFullyValid", result.isFullyValid,
                            "balanceValidation", result.balanceValidation,
                            "continuityValidation", result.continuityValidation,
                            "transactionStats", Map.of(
                                    "total", result.totalTransactions,
                                    "valid", result.validTransactions,
                                    "errors", result.errorTransactions),
                            "errors", result.errors,
                            "warnings", result.warnings));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable("id") Long id,
            @RequestBody UpdateBankStatementStatusRequest request) {
        BankStatus requested = BankStatus.fromExternalValue(request.getStatus());
        if (requested == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Statut invalide",
                    "allowed", List.of("PENDING", "PROCESSING", "TREATED", "READY_TO_VALIDATE", "VALIDATED", "COMPTABILISE",
                            "ERROR", "VIDE", "DUPLIQUE")));
        }

        return repository.findById(id)
                .map(statement -> {
                    try {
                        if (requested == BankStatus.COMPTABILISE) {
                            String userId = request.getUpdatedBy() == null || request.getUpdatedBy().isBlank()
                                    ? "system"
                                    : request.getUpdatedBy();
                            long existingEntries = accountingEntryRepository.countBySourceStatementId(id);
                            if (statement.getStatus() == BankStatus.COMPTABILISE && existingEntries > 0) {
                                int syncedRows = comptabilisationWorkflowService
                                        .syncCptjournalFromExistingAccountingEntries(id);
                                return ResponseEntity.ok(Map.of(
                                        "message", syncedRows > 0
                                                ? "Relevé déjà comptabilisé, synchronisation Cptjournal effectuée"
                                                : "Relevé déjà comptabilisé",
                                        "statement", toResponse(statement),
                                        "insertedEntries", syncedRows));
                            }

                            ComptabilisationWorkflowService.SimulationResult simulation =
                                    comptabilisationWorkflowService.simulate(id);
                            ComptabilisationWorkflowService.ConfirmationResult confirmation =
                                    comptabilisationWorkflowService.confirm(simulation.simulationId(), userId);
                            BankStatement refreshed = repository.findById(id).orElse(statement);

                            return ResponseEntity.ok(Map.of(
                                    "message", "Statut mis à jour",
                                    "statement", toResponse(refreshed),
                                    "insertedEntries", confirmation.insertedEntries(),
                                    "simulationId", confirmation.simulationId()));
                        } else {
                            statement.setStatus(requested);
                            if (requested != BankStatus.COMPTABILISE) {
                                statement.setAccountedAt(null);
                                statement.setAccountedBy(null);
                            }
                            BankStatement saved = repository.save(statement);
                            return ResponseEntity.ok(Map.of(
                                    "message", "Statut mis à jour",
                                    "statement", toResponse(saved)));
                        }
                    } catch (IllegalStateException e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/ttc-rule")
    public ResponseEntity<?> updateTtcRule(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyTtcRule(id, request);
    }

    @PutMapping("/{id}/ttc-toggle")
    public ResponseEntity<?> updateTtcToggle(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyTtcRule(id, request);
    }

    @PostMapping("/{id}/ttc-rule")
    public ResponseEntity<?> updateTtcRulePost(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyTtcRule(id, request);
    }

    @PostMapping("/{id}/ttc-toggle")
    public ResponseEntity<?> updateTtcTogglePost(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyTtcRule(id, request);
    }

    @GetMapping("/{id}/ttc-rule")
    public ResponseEntity<?> updateTtcRuleGet(
            @PathVariable("id") Long id,
            @RequestParam(name = "enabled") boolean enabled,
            @RequestParam(name = "reprocess", defaultValue = "true") boolean reprocess) {
        UpdateTtcRuleRequest request = new UpdateTtcRuleRequest();
        request.setEnabled(enabled);
        request.setReprocess(reprocess);
        return applyTtcRule(id, request);
    }

    @GetMapping("/{id}/ttc-toggle")
    public ResponseEntity<?> updateTtcToggleGet(
            @PathVariable("id") Long id,
            @RequestParam(name = "enabled") boolean enabled,
            @RequestParam(name = "reprocess", defaultValue = "true") boolean reprocess) {
        UpdateTtcRuleRequest request = new UpdateTtcRuleRequest();
        request.setEnabled(enabled);
        request.setReprocess(reprocess);
        return applyTtcRule(id, request);
    }

    private ResponseEntity<?> applyTtcRule(Long id, UpdateTtcRuleRequest request) {
        return repository.findById(id)
                .map(statement -> {
                    boolean enabled = Boolean.TRUE.equals(request.getEnabled());
                    if (!statement.isModifiable()) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Relevé validé/comptabilisé, modification TTC impossible",
                                "statement", toResponse(statement)));
                    }
                    statement.setApplyTtcRule(enabled);
                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        statement.setStatus(BankStatus.PROCESSING);
                        statement.setValidationErrors(null);
                    }
                    BankStatement saved = repository.save(statement);

                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        try {
                            processingService.reprocessStatementAsync(id);
                        } catch (IllegalStateException e) {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "error", e.getMessage(),
                                    "statement", toResponse(saved)));
                        } catch (Exception e) {
                            log.error("Échec lancement retraitement TTC pour relevé {}: {}", id, e.getMessage(), e);
                            return ResponseEntity.ok(Map.of(
                                    "message", enabled ? "Règle TTC activée (retraitement non lancé)"
                                            : "Règle TTC désactivée (retraitement non lancé)",
                                    "warning", "Échec lancement retraitement TTC: " + e.getMessage(),
                                    "statement", toResponse(saved)));
                        }
                    }

                    return ResponseEntity.ok(Map.of(
                            "message", enabled
                                    ? "Règle TTC activée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : "")
                                    : "Règle TTC désactivée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : ""),
                            "statement", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== STATISTIQUES ====================

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<?> stats() {
        try {
            var stats = processingService.getStatistics();

            return ResponseEntity.ok(Map.of(
                    "total", stats.totalStatements,
                    "pending", stats.pendingStatements,
                    "processing", stats.processingStatements,
                    "treated", stats.treatedStatements,
                    "readyToValidate", stats.readyStatements,
                    "validated", stats.validatedStatements,
                    "accounted", stats.accountedStatements,
                    "error", stats.errorStatements,
                    "totalRibs", stats.totalRibs));
        } catch (Exception e) {
            log.error("❌ Erreur stats: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "Erreur stats: " + e.getMessage()));
        }
    }

    @GetMapping("/bank-options")
    public ResponseEntity<?> bankOptions() {
        return ResponseEntity.ok(Map.of(
                "count", BankAliasResolver.bankChoices().size(),
                "options", BankAliasResolver.bankChoices()));
    }

    // ==================== FICHIERS ====================

    @GetMapping("/files/{filename}")
    @CrossOrigin("*")
    public ResponseEntity<Resource> getFile(@PathVariable("filename") String filename) {
        try {
            Optional<BankStatement> statementOpt = repository.findFirstByFilenameOrderByIdDesc(filename);
            if (statementOpt.isEmpty() || statementOpt.get().getFileData() == null || statementOpt.get().getFileData().length == 0) {
                return ResponseEntity.notFound().build();
            }

            BankStatement statement = statementOpt.get();
            Resource resource = new ByteArrayResource(statement.getFileData());
            String contentType = statement.getFileContentType() != null
                    ? statement.getFileContentType()
                    : "application/octet-stream";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("❌ Erreur récupération fichier: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==================== HELPERS ====================

    private boolean isValidExtension(String filename) {
        if (filename == null)
            return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".pdf") ||
                lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    private boolean isValidMimeType(String contentType) {
        if (contentType == null)
            return false;
        String ct = contentType.toLowerCase();
        return ct.equals("image/jpeg") || ct.equals("image/jpg") ||
                ct.equals("image/png") || ct.equals("application/pdf") ||
                ct.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ||
                ct.equals("application/vnd.ms-excel") ||
                ct.contains("spreadsheet") || ct.equals("application/octet-stream");
    }

    private List<String> normalizeAllowedBanks(List<String> allowedBanks) {
        List<String> cleanedAllowedBanks = new ArrayList<>();
        if (allowedBanks == null) {
            return cleanedAllowedBanks;
        }
        for (String bank : allowedBanks) {
            if (bank == null) {
                continue;
            }
            if (bank.contains(",")) {
                cleanedAllowedBanks.addAll(Arrays.asList(bank.split(",")));
            } else {
                cleanedAllowedBanks.add(bank);
            }
        }
        return cleanedAllowedBanks.stream()
                .map(BankAliasResolver::normalizeAllowedBankCode)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private Map<String, Object> toResponse(BankStatement statement) {
        return buildListResponse(statement);
    }

    private Map<String, Object> toDetailedResponse(BankStatement statement) {
        BankStatement source = resolveDisplaySource(statement);

        Map<String, Object> response = buildListResponse(statement);
        response.put("accountHolder", source.getAccountHolder());
        response.put("balanceDifference", source.getBalanceDifference());
        response.put("validationErrors", statement.getValidationErrors());
        response.put("filePath", statement.getFilePath());
        response.put("fileSize", statement.getFileSize());
        response.put("validatedAt", statement.getValidatedAt());
        response.put("validatedBy", statement.getValidatedBy());
        response.put("rawOcrText", source.getRawOcrText());
        response.put("cleanedOcrText", source.getCleanedOcrText());
        Map<String, String> accountLabelsByCode = accountLearningService.findAccountLibelles(
                source.getTransactions().stream()
                        .map(this::resolveDisplayedCompte)
                        .toList());

        List<Map<String, Object>> transactions = source.getTransactions().stream()
                .map(t -> {
                    String[] ocrDates = resolveDisplayDates(t);
                    Map<String, Object> txMap = new LinkedHashMap<>();
                    txMap.put("id", t.getId());
                    txMap.put("date", ocrDates[0]);
                    txMap.put("dateOperation", ocrDates[0]);
                    txMap.put("dateValeur", ocrDates[1]);
                    txMap.put("libelle", t.getLibelle());
                    txMap.put("description", t.getLibelle());
                    txMap.put("debit", t.getDebit() != null ? t.getDebit() : 0);
                    txMap.put("credit", t.getCredit() != null ? t.getCredit() : 0);
                    txMap.put("balance", t.getBalance() != null ? t.getBalance() : 0);
                    txMap.put("confidenceScore", t.getConfidenceScore() != null ? t.getConfidenceScore() : 0);
                    txMap.put("flags", t.getFlags() != null ? t.getFlags() : List.of());
                    txMap.put("transactionIndex", t.getTransactionIndex());
                    String displayedCompte = resolveDisplayedCompte(t);
                    txMap.put("compte", displayedCompte);
                    txMap.put("compteLibelle", accountLabelsByCode.getOrDefault(displayedCompte, ""));
                    txMap.put("isLinked", displayIsLinked(t.getIsLinked(), displayedCompte));
                    txMap.put("sens", t.getSens());
                    txMap.put("isValid", t.getIsValid());
                    return txMap;
                })
                .toList();
        response.put("transactions", transactions);
        response.put("transactionsPreview", transactions);

        return response;
    }

    private Map<String, Object> buildListResponse(BankStatement statement) {
        BankStatement source = resolveDisplaySource(statement);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", statement.getId());
        response.put("filename", statement.getFilename());
        response.put("originalName", statement.getOriginalName());
        response.put("status", mapDisplayStatus(statement));
        response.put("statusCode", statement.getStatus() != null ? statement.getStatus().name() : "UNKNOWN");
        response.put("rib", source.getRib());
        response.put("month", source.getMonth());
        response.put("year", source.getYear());
        response.put("bankName", source.getBankName());
        response.put("applyTtcRule", Boolean.TRUE.equals(statement.getApplyTtcRule()));
        response.put("openingBalance", source.getOpeningBalance());
        response.put("closingBalance", source.getClosingBalance());
        response.put("totalCredit", source.getTotalCredit());
        response.put("totalDebit", source.getTotalDebit());
        response.put("balanceDifference", source.getBalanceDifference());
        response.put("transactionCount", source.getTransactionCount());
        response.put("validTransactionCount", source.getValidTransactionCount());
        response.put("errorTransactionCount", source.getErrorTransactionCount());
        response.put("overallConfidence", source.getOverallConfidence());
        response.put("continuityStatus",
                source.getContinuityStatus() != null ? source.getContinuityStatus().name() : "UNKNOWN");
        response.put("isBalanceValid", source.getIsBalanceValid());
        response.put("isContinuityValid", source.getIsContinuityValid());
        response.put("isLinked", false);
        response.put("canReprocess", statement.isModifiable() && statement.getStatus() != BankStatus.PROCESSING);
        response.put("canDelete", true);
        response.put("createdAt", statement.getCreatedAt());
        response.put("updatedAt", statement.getUpdatedAt());
        response.put("accountedAt", statement.getAccountedAt());
        response.put("accountedBy", statement.getAccountedBy());

        if (Boolean.TRUE.equals(statement.getIsDuplicate()) || statement.getStatus() == BankStatus.DUPLIQUE) {
            Long sourceId = extractDuplicateOfId(statement.getValidationErrors());
            response.put("alertType", "danger");
            response.put("alertMessage", "Ce relevé est un doublon du relevé #" + (sourceId != null ? sourceId : "?"));
            response.put("duplicateOfId", sourceId);
        }

        return response;
    }

    private Map<String, Object> buildFinalResponse(BankStatement statement, boolean includeTransactions) {
        BankStatement source = resolveDisplaySource(statement);
        List<BankTransaction> txs = source.getTransactions() != null ? source.getTransactions() : List.of();
        List<BankTransaction> txsWithOperationDate = txs.stream()
                .filter(t -> t.getDateOperation() != null)
                .toList();

        if (txsWithOperationDate.isEmpty()) {
            return Map.of("erreur", "Aucune date d'opération détectée");
        }

        LocalDate dateMin = txsWithOperationDate.stream()
                .map(BankTransaction::getDateOperation)
                .min(LocalDate::compareTo)
                .orElse(null);
        LocalDate dateMax = txsWithOperationDate.stream()
                .map(BankTransaction::getDateOperation)
                .max(LocalDate::compareTo)
                .orElse(null);

        String periode = formatPeriod(source.getMonth(), source.getYear());
        if ((periode == null || periode.isBlank()) && dateMin != null) {
            periode = formatPeriod(dateMin.getMonthValue(), dateMin.getYear());
        }

        BigDecimal totalDecaissement = txs.stream()
                .map(t -> t.getDebit() != null ? t.getDebit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEncaissement = txs.stream()
                .map(t -> t.getCredit() != null ? t.getCredit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("banque", source.getBankName() != null ? source.getBankName() : "");
        response.put("rib", source.getRib() != null ? source.getRib() : "");
        response.put("periode", periode);
        response.put("date_debut", dateMin != null ? dateMin.toString() : "");
        response.put("date_fin", dateMax != null ? dateMax.toString() : "");
        response.put("total_decaissement", totalDecaissement);
        response.put("total_encaissement", totalEncaissement);
        response.put("nombre_operations", txsWithOperationDate.size());
        response.put("statut", statement.getStatus() == BankStatus.COMPTABILISE ? "COMPTABILISE" : "PRET_A_VALIDER");
        return response;
    }

    private String formatStatementDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.format(STATEMENT_DATE_FORMATTER);
    }

    private String[] resolveDisplayDates(BankTransaction transaction) {
        String dateOperation = formatStatementDate(transaction.getDateOperation());
        String dateValeur = formatStatementDate(transaction.getDateValeur());
        String raw = transaction.getRawOcrLine();

        if (!dateOperation.isBlank() && !dateValeur.isBlank()) {
            return new String[] { dateOperation, dateValeur };
        }

        if (raw == null || raw.isBlank()) {
            return new String[] { dateOperation, dateValeur };
        }

        Matcher matcher = OCR_DATE_PATTERN.matcher(raw);
        List<String> foundDates = new ArrayList<>();
        while (matcher.find() && foundDates.size() < 2) {
            String captured = matcher.group(1);
            LocalDate parsed = parseOcrDisplayDate(captured);
            if (parsed != null) {
                foundDates.add(formatStatementDate(parsed));
            }
        }

        if (!foundDates.isEmpty()) {
            if (dateOperation.isBlank()) {
                dateOperation = foundDates.get(0);
            }
            if (dateValeur.isBlank()) {
                dateValeur = foundDates.size() > 1 ? foundDates.get(1) : foundDates.get(0);
            }
        }

        return new String[] { dateOperation, dateValeur };
    }

    private LocalDate parseOcrDisplayDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }

        String normalized = rawDate.trim()
                .replaceAll("\\s*[\\/\\-.]\\s*", "/")
                .replaceAll("\\s+", "/");

        String[] parts = normalized.split("/");
        if (parts.length != 3) {
            return null;
        }

        try {
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int year = Integer.parseInt(parts[2]);
            if (year < 100) {
                year += 2000;
            }
            if (day < 1 || day > 31 || month < 1 || month > 12 || year < 1900 || year > 2100) {
                return null;
            }
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    private BankStatement resolveDisplaySource(BankStatement statement) {
        if (statement == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(statement.getIsDuplicate()) && statement.getStatus() != BankStatus.DUPLIQUE) {
            return statement;
        }

        Long duplicateOfId = extractDuplicateOfId(statement.getValidationErrors());
        if (duplicateOfId != null) {
            return repository.findById(duplicateOfId).orElse(statement);
        }

        if (statement.getDuplicateHash() != null && !statement.getDuplicateHash().isBlank()) {
            return repository.findAllByDuplicateHashOrderByCreatedAtDescIdDesc(statement.getDuplicateHash())
                    .stream()
                    .filter(candidate -> !Objects.equals(candidate.getId(), statement.getId()))
                    .findFirst()
                    .orElse(statement);
        }
        return statement;
    }

    private Long extractDuplicateOfId(String validationErrors) {
        if (validationErrors == null) {
            return null;
        }
        Matcher matcher = DUPLICATE_OF_PATTERN.matcher(validationErrors);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatPeriod(Integer month, Integer year) {
        if (month == null || year == null) {
            return "";
        }
        return String.format("%02d/%04d", month, year);
    }

    private String displayCompte(String compte) {
        if (compte == null || compte.isBlank()) {
            return DEFAULT_COMPTE;
        }
        return compte.trim();
    }

    private String resolveDisplayedCompte(BankTransaction transaction) {
        String current = displayCompte(transaction.getCompte());
        if (!DEFAULT_COMPTE.equals(current)) {
            return current;
        }
        return accountLearningService.findSuggestedAccount(transaction.getLibelle()).orElse(current);
    }

    private boolean displayIsLinked(Boolean isLinked, String displayedCompte) {
        if (Boolean.TRUE.equals(isLinked)) {
            return true;
        }
        return displayedCompte != null && !displayedCompte.isBlank();
    }

    private String mapStatus(BankStatus status) {
        if (status == null) {
            return "TRAITE";
        }
        return switch (status) {
            case PENDING -> "EN_ATTENTE";
            case PROCESSING -> "EN_COURS";
            case READY_TO_VALIDATE -> "PRET_A_VALIDER";
            case TREATED -> "TRAITE";
            case VALIDATED -> "VALIDE";
            case COMPTABILISE -> "COMPTABILISE";
            case ERROR -> "ERREUR";
            case VIDE -> "VIDE";
            case DUPLIQUE -> "DUPLIQUE";
            default -> "TRAITE";
        };
    }

    private String mapDisplayStatus(BankStatement statement) {
        if (statement == null) {
            return "TRAITE";
        }

        // Ne pas masquer les statuts techniques.
        if (statement.getStatus() == BankStatus.ERROR
                || statement.getStatus() == BankStatus.PROCESSING
                || statement.getStatus() == BankStatus.PENDING
                || statement.getStatus() == BankStatus.VALIDATED
                || statement.getStatus() == BankStatus.COMPTABILISE
                || statement.getStatus() == BankStatus.READY_TO_VALIDATE) {
            return mapStatus(statement.getStatus());
        }

        if (Boolean.TRUE.equals(statement.getIsDuplicate())) {
            return "DUPLIQUE";
        }
        boolean isEmpty = (statement.getRib() == null || statement.getRib().isBlank())
                && statement.getTransactionCount() == 0
                && statement.getTotalDebitPdf() == null
                && statement.getTotalCreditPdf() == null;
        if (isEmpty) {
            return "VIDE";
        }
        return mapStatus(statement.getStatus());
    }

    public static class UpdateBankStatementStatusRequest {
        private String status;
        private String updatedBy;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(String updatedBy) {
            this.updatedBy = updatedBy;
        }
    }

    public static class UpdateTtcRuleRequest {
        private Boolean enabled;
        private Boolean reprocess = true;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Boolean getReprocess() {
            return reprocess;
        }

        public void setReprocess(Boolean reprocess) {
            this.reprocess = reprocess;
        }
    }
}
