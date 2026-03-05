package com.example.releve_bancaire.controller.dynamic;

import com.example.releve_bancaire.dto.dynamic.CreateDynamicTemplateRequest;
import com.example.releve_bancaire.dto.dynamic.DynamicExtractionResponse;
import com.example.releve_bancaire.dto.dynamic.DynamicTemplateDto;
import com.example.releve_bancaire.dto.dynamic.UpdateDynamicTemplateRequest;
import com.example.releve_bancaire.entity.dynamic.DynamicInvoice;
import com.example.releve_bancaire.entity.dynamic.DynamicTemplate;
import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.repository.DynamicInvoiceDao;
import com.example.releve_bancaire.servises.dynamic.DynamicExtractionResult;
import com.example.releve_bancaire.servises.dynamic.DynamicFieldExtractorService;
import com.example.releve_bancaire.servises.dynamic.DynamicTemplateService;
import com.example.releve_bancaire.servises.ocr.AdvancedOcrService;
import com.example.releve_bancaire.dto.ocr.OcrResult;
import com.example.releve_bancaire.utils.LogHelper;
import com.example.releve_bancaire.utils.InvoiceTypeDetector;
import com.example.releve_bancaire.security.RequireRole;
import jakarta.validation.Valid;
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
import java.util.*;

/**
 * API REST pour les templates dynamiques et l'extraction intelligente.
 *
 * @version 2.0 - Utilisation DynamicInvoice + AdvancedOcrService
 */
@RestController
@RequestMapping("/api/dynamic-templates")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
@RequireRole({ UserRole.ADMIN })
public class DynamicTemplateController {

    private final DynamicTemplateService dynamicTemplateService;
    private final DynamicFieldExtractorService extractorService;
    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final AdvancedOcrService advancedOcrService;

    // ==================== CRUD TEMPLATES ====================

    /**
     * Crée un nouveau template dynamique.
     */
    @PostMapping
    public ResponseEntity<?> createTemplate(@Valid @RequestBody CreateDynamicTemplateRequest request) {
        try {
            DynamicTemplate template = dynamicTemplateService.createTemplate(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(DynamicTemplateDto.fromEntity(template));
        } catch (IllegalArgumentException e) {
            log.error("Erreur création template: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Met à jour un template (crée nouvelle version).
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody CreateDynamicTemplateRequest request) {
        try {
            DynamicTemplate template = dynamicTemplateService.updateTemplate(id, request);
            return ResponseEntity.ok(DynamicTemplateDto.fromEntity(template));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Récupère un template par ID.
     */
    @GetMapping("/{id}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> getTemplate(@PathVariable Long id) {
        return dynamicTemplateService.findById(id)
                .map(t -> ResponseEntity.ok(DynamicTemplateDto.fromEntity(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Liste tous les templates actifs.
     */
    @GetMapping
    public ResponseEntity<List<DynamicTemplateDto>> getAllTemplates() {
        List<DynamicTemplateDto> templates = dynamicTemplateService.findAllActive().stream()
                .map(DynamicTemplateDto::fromEntity)
                .toList();
        return ResponseEntity.ok(templates);
    }

    /**
     * Recherche par nom.
     */
    @GetMapping("/search")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<List<DynamicTemplateDto>> searchTemplates(@RequestParam String name) {
        List<DynamicTemplateDto> templates = dynamicTemplateService.searchByName(name).stream()
                .map(DynamicTemplateDto::fromEntity)
                .toList();
        return ResponseEntity.ok(templates);
    }

    /**
     * Liste par type de fournisseur.
     */
    @GetMapping("/by-supplier-type/{supplierType}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<List<DynamicTemplateDto>> getBySupplierType(@PathVariable String supplierType) {
        List<DynamicTemplateDto> templates = dynamicTemplateService.findBySupplierType(supplierType).stream()
                .map(DynamicTemplateDto::fromEntity)
                .toList();
        return ResponseEntity.ok(templates);
    }

    /**
     * Templates les plus fiables.
     */
    @GetMapping("/reliable")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<List<DynamicTemplateDto>> getReliableTemplates() {
        List<DynamicTemplateDto> templates = dynamicTemplateService.findReliableTemplates().stream()
                .map(DynamicTemplateDto::fromEntity)
                .toList();
        return ResponseEntity.ok(templates);
    }

    /**
     * Recherche par nom de fournisseur.
     * Un fournisseur peut avoir plusieurs templates (plusieurs ICE).
     */
    @GetMapping("/by-supplier")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> searchBySupplier(@RequestParam String supplier) {
        List<DynamicTemplateDto> templates = dynamicTemplateService.searchBySupplier(supplier).stream()
                .map(DynamicTemplateDto::fromEntity)
                .toList();

        return ResponseEntity.ok(Map.of(
                "supplier", supplier,
                "count", templates.size(),
                "templates", templates));
    }

    /**
     * Désactive un template.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateTemplate(@PathVariable Long id) {
        dynamicTemplateService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    // ==================== EXTRACTION ====================

    /**
     * Extrait les données d'une facture existante avec un template spécifique.
     *
     * ✅ CORRECTION: Utilise DynamicInvoice avec getRawOcrText()
     */
    /**
     * Extrait les données d'une facture existante avec un template spécifique.
     * VERSION CORRIGÉE: Retourne rawOcrText pour affichage frontend
     */
    @PostMapping("/extract/{invoiceId}")
    public ResponseEntity<?> extractWithTemplate(
            @PathVariable Long invoiceId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(required = false) Long templateId) {

        LogHelper.logExtractionStart(templateId != null, templateId);

        try {
            if (dossierId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
            }

            // 1. Récupérer la facture
            DynamicInvoice invoice = dynamicInvoiceDao.findById(invoiceId)
                    .orElseThrow(() -> new IllegalArgumentException("Facture non trouvée: " + invoiceId));
            if (invoice.getDossierId() == null || !dossierId.equals(invoice.getDossierId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "dossier_forbidden"));
            }

            // 2. Vérifier que le texte OCR existe
            // 2. Vérifier que le texte OCR existe
            String ocrText = invoice.getRawOcrText();

            // TENTATIVE D'AUTO-RÉPARATION SI OCR MANQUANT
            if (ocrText == null || ocrText.isEmpty()) {
                if (invoice.getFilePath() != null) {
                    File file = new File(invoice.getFilePath());
                    if (file.exists()) {
                        log.info("[EXTRACTION] OCR manquant. Tentative d'auto-réparation depuis: {}",
                                invoice.getFilePath());
                        try {
                            OcrResult ocrResult = advancedOcrService.extractTextAdvanced(file.toPath());
                            if (ocrResult.isSuccess() && ocrResult.getText() != null
                                    && !ocrResult.getText().isBlank()) {
                                ocrText = ocrResult.getText();
                                invoice.setRawOcrText(ocrText);
                                dynamicInvoiceDao.save(invoice);
                                log.info("[EXTRACTION] Auto-réparation réussie. {} caractères extraits.",
                                        ocrText.length());
                            }
                        } catch (Exception e) {
                            log.error("[EXTRACTION] Echec auto-réparation OCR: {}", e.getMessage());
                        }
                    }
                }
            }

            if (ocrText == null || ocrText.isEmpty()) {
                log.error("[EXTRACTION] No OCR text available: invoiceId={}", invoiceId);
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Texte OCR non disponible pour cette facture",
                        "errorCode", "EXTRACTION_400",
                        "invoiceId", invoiceId));
            }

            // 3. Trouver le template
            DynamicTemplate template;
            if (templateId != null) {
                // Template forcé
                template = dynamicTemplateService.findById(templateId)
                        .orElseThrow(() -> new IllegalArgumentException("Template non trouvé: " + templateId));

                log.info("[EXTRACTION] Using forced template: id={}, name='{}'",
                        templateId, template.getTemplateName());
            } else {
                // Détection automatique
                template = dynamicTemplateService.detectTemplateBySignature(ocrText)
                        .orElse(null);

                if (template != null) {
                    log.info("[EXTRACTION] Template auto-detected: id={}, name='{}'",
                            template.getId(), template.getTemplateName());
                } else {
                    log.warn("[EXTRACTION] No template detected for invoice: id={}", invoiceId);
                }
            }

            // 4. Extraction
            DynamicExtractionResult result;
            if (template != null) {
                result = extractorService.extractWithTemplate(ocrText, template);
                dynamicTemplateService.recordUsage(template.getId(), result.isComplete());
            } else {
                result = extractorService.extractWithoutTemplate(ocrText);
            }

            // 5. Mettre à jour la facture
            invoice.setFieldsData(result.toSimpleMap());
            invoice.setOverallConfidence(result.getOverallConfidence());
            invoice.setMissingFields(result.getMissingFields());
            invoice.setLowConfidenceFields(result.getLowConfidenceFields());
            invoice.setIsAvoir(InvoiceTypeDetector.isAvoir(invoice.getFieldsData(), invoice.getRawOcrText()));

            if (template != null) {
                invoice.setTemplateId(template.getId());
                invoice.setTemplateName(template.getTemplateName());
            }

            dynamicInvoiceDao.save(invoice);

            LogHelper.logExtractionComplete(
                    result.getExtractedCount(),
                    result.getMissingCount(),
                    result.getOverallConfidence());

            // 6. ✅ CORRECTION: Construire réponse avec rawOcrText
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", result.isComplete()
                    ? "Extraction complète"
                    : "Extraction partielle - champs manquants");
            response.put("invoiceId", invoiceId);
            response.put("templateId", template != null ? template.getId() : null);
            response.put("templateName", template != null ? template.getTemplateName() : "DEFAULT");

            // ✅ CRITIQUE: Ajouter rawOcrText et extractedText (avec null-safety)
            response.put("rawOcrText", ocrText != null ? ocrText : "");
            response.put("extractedText", ocrText != null ? ocrText : "");

            // Champs extraits (avec null-safety)
            Map<String, Object> extractedFieldsFormatted = new LinkedHashMap<>();
            if (result.getExtractedFields() != null) {
                result.getExtractedFields().forEach((key, field) -> {
                    extractedFieldsFormatted.put(key, Map.of(
                            "value", field.getValue() != null ? field.getValue() : "",
                            "normalizedValue", field.getNormalizedValue() != null ? field.getNormalizedValue() : "",
                            "confidence", field.getConfidence() != null ? field.getConfidence() : 0.0,
                            "detectionMethod",
                            field.getDetectionMethod() != null ? field.getDetectionMethod() : "UNKNOWN",
                            "validated", field.getValidated() != null ? field.getValidated() : false));
                });
            }

            response.put("extractedFields", extractedFieldsFormatted);
            response.put("missingFields",
                    result.getMissingFields() != null ? result.getMissingFields() : Collections.emptyList());
            response.put("lowConfidenceFields",
                    result.getLowConfidenceFields() != null ? result.getLowConfidenceFields()
                            : Collections.emptyList());
            response.put("overallConfidence",
                    result.getOverallConfidence() != null ? result.getOverallConfidence() : 0.0);
            response.put("extractedCount", result.getExtractedCount());
            response.put("totalFields", result.getExtractedCount() + result.getMissingCount());
            response.put("isComplete", result.isComplete());
            response.put("status", invoice.getStatus() != null ? invoice.getStatus().name() : "UNKNOWN");
            response.put("isAvoir", Boolean.TRUE.equals(invoice.getIsAvoir()));

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("[EXTRACTION] Validation error: invoiceId={}, error='{}'",
                    invoiceId, e.getMessage());

            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "errorCode", "EXTRACTION_400",
                    "invoiceId", invoiceId));

        } catch (Exception e) {
            log.error("[EXTRACTION] Unexpected error: invoiceId={}, error='{}'",
                    invoiceId, e.getMessage(), e);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Erreur extraction: " + e.getMessage(),
                    "errorCode", "EXTRACTION_500",
                    "invoiceId", invoiceId));
        }
    }

    /**
     * Extrait les données d'un fichier uploadé directement.
     *
     * CORRECTION: Utilise AdvancedOcrService (ou gardez OcrService si pas encore
     * migré)
     */
    @PostMapping("/extract-file")
    public ResponseEntity<?> extractFromFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long templateId) {

        File tempFile = null;
        try {
            // Sauvegarder le fichier temporairement
            tempFile = Files.createTempFile("invoice_", "_" + file.getOriginalFilename()).toFile();
            file.transferTo(tempFile);

            // OPTION A: Utiliser AdvancedOcrService (RECOMMANDÉ)
            Path tempPath = tempFile.toPath();
            OcrResult ocrResult = advancedOcrService.extractTextAdvanced(tempPath);
            String ocrText = ocrResult.getText();

            // OPTION B: Utiliser ancien OcrService (si pas encore migré)
            // String ocrText = ocrService.extractText(tempFile);

            if (ocrText == null || ocrText.isBlank()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Impossible d'extraire le texte du fichier"));
            }

            log.info("OCR réussi: {} caractères, {}% confiance",
                    ocrText.length(), ocrResult.getConfidence());

            // Trouver le template
            DynamicTemplate template;
            if (templateId != null) {
                template = dynamicTemplateService.findById(templateId)
                        .orElseThrow(() -> new IllegalArgumentException("Template non trouvé: " + templateId));
            } else {
                template = dynamicTemplateService.detectTemplate(ocrText).orElse(null);
            }

            // Extraction
            DynamicExtractionResult result;
            if (template != null) {
                result = extractorService.extractWithTemplate(ocrText, template);
                dynamicTemplateService.recordUsage(template.getId(), result.isComplete());
            } else {
                result = extractorService.extractWithoutTemplate(ocrText);
            }

            Map<String, Object> extracted = result.toSimpleMap();

            // Ajouter métadonnées OCR dans la réponse
            Map<String, Object> response = new HashMap<>();
            response.put("extraction", DynamicExtractionResponse.fromResult(result, null));
            response.put("coreFields", filterCoreFields(extracted));
            response.put("ocrConfidence", ocrResult.getConfidence());
            response.put("ocrProcessingTimeMs", ocrResult.getProcessingTimeMs());
            response.put("templateDetected", template != null);
            if (template != null) {
                response.put("templateName", template.getTemplateName());
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("Erreur IO fichier: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur traitement fichier: " + e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Erreur extraction OCR: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur OCR: " + e.getMessage()));
        } finally {
            if (tempFile != null && tempFile.exists()) {
                boolean deleted = tempFile.delete();
                if (!deleted) {
                    log.warn("Impossible de supprimer fichier temporaire: {}", tempFile.getAbsolutePath());
                }
            }
        }
    }

    /**
     * Teste un template sur un texte OCR.
     */
    @PostMapping("/{templateId}/test")
    public ResponseEntity<?> testTemplate(
            @PathVariable Long templateId,
            @RequestBody Map<String, String> body) {

        String ocrText = body.get("ocrText");
        if (ocrText == null || ocrText.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ocrText requis"));
        }

        Optional<DynamicTemplate> templateOpt = dynamicTemplateService.findById(templateId);
        if (templateOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DynamicExtractionResult result = extractorService.extractWithTemplate(ocrText, templateOpt.get());
        return ResponseEntity.ok(result.toDetailedMap());
    }

    /**
     * Détecte automatiquement le template pour un texte OCR.
     */
    @PostMapping("/detect")
    public ResponseEntity<?> detectTemplate(@RequestBody Map<String, String> body) {
        String ocrText = body.get("ocrText");
        if (ocrText == null || ocrText.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ocrText requis"));
        }

        Optional<DynamicTemplate> template = dynamicTemplateService.detectTemplate(ocrText);
        if (template.isPresent()) {
            return ResponseEntity.ok(Map.of(
                    "detected", true,
                    "template", DynamicTemplateDto.fromEntity(template.get())));
        }

        return ResponseEntity.ok(Map.of("detected", false, "message", "Aucun template détecté"));
    }

    /**
     * Mise à jour partielle d'un template (renommer, changer supplierType, etc.)
     * sans créer de nouvelle version.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> patchTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDynamicTemplateRequest request) {
        try {
            DynamicTemplate template = dynamicTemplateService.patchTemplate(id, request);
            return ResponseEntity.ok(DynamicTemplateDto.fromEntity(template));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> filterCoreFields(Map<String, Object> extracted) {
        Map<String, Object> core = new LinkedHashMap<>();
        if (extracted == null || extracted.isEmpty()) {
            return core;
        }
        core.put("invoiceNumber", extracted.get("invoiceNumber"));
        core.put("invoiceDate", extracted.get("invoiceDate"));
        core.put("amountHT", extracted.get("amountHT"));
        core.put("tva", extracted.get("tva"));
        core.put("amountTTC", extracted.get("amountTTC"));
        core.put("ice", extracted.get("ice"));
        core.put("ifNumber", extracted.get("ifNumber"));
        core.put("rcNumber", extracted.get("rcNumber"));
        return core;
    }
}
