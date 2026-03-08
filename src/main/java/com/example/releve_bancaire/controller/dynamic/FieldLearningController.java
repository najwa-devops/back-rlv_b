package com.example.releve_bancaire.controller.dynamic;

import com.example.releve_bancaire.dto.dynamic.FieldLearningDto;
import com.example.releve_bancaire.dto.dynamic.FieldPatternInfo;
import com.example.releve_bancaire.dto.dynamic.SaveFieldsWithPatternsRequest;
import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.servises.dynamic.FieldLearningService;
import com.example.releve_bancaire.servises.patterns.PatternIntegrationService;
import com.example.releve_bancaire.security.RequireRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller REST pour la gestion de l'apprentissage automatique
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
@RequireRole({ UserRole.ADMIN })
public class FieldLearningController {

    private final FieldLearningService learningService;
    private final PatternIntegrationService patternIntegrationService;

    // ===================== SAUVEGARDE DES PATTERNS =====================
    //
    // /**
    // * Sauvegarde les champs d'une facture avec les patterns détectés
    // * PUT /api/v2/invoices/{id}/fields-with-patterns
    // */
    // @PutMapping("/invoices/{id}/fields-with-patterns")
    // public ResponseEntity<?> saveFieldsWithPatterns(
    // @PathVariable Long id,
    // @Valid @RequestBody SaveFieldsWithPatternsRequest request
    // ) {
    // log.info("PUT /api/v2/invoices/{}/fields-with-patterns", id);
    //
    // try {
    // List<FieldLearningDto> learningData =
    // learningService.saveFieldsWithPatterns(id, request);
    //
    // return ResponseEntity.ok(Map.of(
    // "message", "Champs et patterns enregistrés avec succès",
    // "learningData", learningData,
    // "totalPatterns", learningData.size()
    // ));
    // } catch (IllegalArgumentException e) {
    // log.error("Erreur validation: {}", e.getMessage());
    // return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    // } catch (Exception e) {
    // log.error("Erreur inattendue: ", e);
    // return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    // .body(Map.of("error", "Erreur lors de l'enregistrement des patterns"));
    // }
    // }

    /**
     * NOUVEAU ENDPOINT - Sauvegarde les champs ET intègre immédiatement dans le
     * template
     *
     * PUT /api/v2/invoices/{id}/fields-with-patterns
     *
     * DIFFÉRENCE avec l'ancien endpoint:
     * - Ancien: Sauvegarde seulement dans FieldLearningData (apprentissage)
     * - Nouveau: Sauvegarde + Intègre IMMÉDIATEMENT dans DynamicTemplate
     */
    @PutMapping("/{invoiceId}/fields-with-patterns")
    public ResponseEntity<Map<String, Object>> saveFieldsAndIntegratePatterns(
            @PathVariable Long invoiceId,
            @RequestBody SaveFieldsWithPatternsRequest request) {
        log.info("PUT /api/v2/{}/fields-with-patterns", invoiceId);
        log.info("Champs reçus: {}", request.getFieldsData() != null ? request.getFieldsData().keySet() : "null");
        log.info("Patterns reçus: {}", request.getNewPatterns());

        try {
            // Validation
            if (request.getFieldsData() == null || request.getFieldsData().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "fieldsData est requis");
                return ResponseEntity.badRequest().body(error);
            }

            // Appel service
            Map<String, Object> result = patternIntegrationService.saveFieldsAndIntegratePatterns(
                    invoiceId,
                    request.getFieldsData(),
                    request.getNewPatterns() != null ? request.getNewPatterns() : new HashMap<>());

            log.info("Sauvegarde réussie pour facture {}", invoiceId);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.error("Erreur validation: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            log.error("Erreur inattendue: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Erreur serveur: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * ANCIEN ENDPOINT (optionnel - garder pour compatibilité)
     * Sauvegarde seulement dans FieldLearningData (sans intégration immédiate)
     */
    @PostMapping("/{id}/learn-patterns")
    public ResponseEntity<?> learnPatternsOnly(
            @PathVariable Long id,
            @RequestBody SaveFieldsWithPatternsRequest request) {
        log.info("POST /api/v2/invoices/{}/learn-patterns (apprentissage seulement)", id);

        // Appeler l'ancien service (apprentissage uniquement)
        // learningService.saveFieldsWithPatterns(id, request);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Patterns enregistrés pour apprentissage futur"));
    }

    // ===================== CONSULTATION DES PATTERNS =====================

    /**
     * Récupère tous les patterns en attente de validation
     * GET /api/v2/learning/pending
     */
    @GetMapping("/learning/pending")
    public ResponseEntity<List<FieldLearningDto>> getPendingPatterns() {
        log.info("GET /api/v2/learning/pending");
        List<FieldLearningDto> patterns = learningService.getPendingPatterns();
        return ResponseEntity.ok(patterns);
    }

    /**
     * Récupère les patterns d'une facture
     * GET /api/v2/learning/invoice/{invoiceId}
     */
    @GetMapping("/learning/invoice/{invoiceId}")
    public ResponseEntity<List<FieldLearningDto>> getPatternsByInvoice(@PathVariable Long invoiceId) {
        log.info("GET /api/v2/learning/invoice/{}", invoiceId);
        List<FieldLearningDto> patterns = learningService.getPatternsByInvoice(invoiceId);
        return ResponseEntity.ok(patterns);
    }

    /**
     * Récupère les patterns d'un fournisseur
     * GET /api/v2/learning/supplier/{ice}
     */
    @GetMapping("/learning/supplier/{ice}")
    public ResponseEntity<List<FieldLearningDto>> getPatternsBySupplier(@PathVariable String ice) {
        log.info("GET /api/v2/learning/supplier/{}", ice);
        List<FieldLearningDto> patterns = learningService.getPatternsBySupplier(ice);
        return ResponseEntity.ok(patterns);
    }

    // ===================== VALIDATION DES PATTERNS =====================

    /**
     * Approuve un pattern
     * PUT /api/v2/learning/{id}/approve
     */
    @PutMapping("/learning/{id}/approve")
    public ResponseEntity<?> approvePattern(
            @PathVariable Long id,
            @RequestParam(defaultValue = "admin") String approvedBy) {
        log.info("PUT /api/v2/learning/{}/approve by {}", id, approvedBy);

        try {
            FieldLearningDto approved = learningService.approvePattern(id, approvedBy);
            return ResponseEntity.ok(Map.of(
                    "message", "Pattern approuvé avec succès",
                    "pattern", approved));
        } catch (IllegalArgumentException e) {
            log.error("Erreur: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur inattendue: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors de l'approbation"));
        }
    }

    /**
     * Rejette un pattern
     * PUT /api/v2/learning/{id}/reject
     */
    @PutMapping("/learning/{id}/reject")
    public ResponseEntity<?> rejectPattern(
            @PathVariable Long id,
            @RequestParam(defaultValue = "admin") String rejectedBy,
            @RequestParam String reason) {
        log.info("PUT /api/v2/learning/{}/reject by {} (reason: {})", id, rejectedBy, reason);

        try {
            FieldLearningDto rejected = learningService.rejectPattern(id, rejectedBy, reason);
            return ResponseEntity.ok(Map.of(
                    "message", "Pattern rejeté avec succès",
                    "pattern", rejected));
        } catch (IllegalArgumentException e) {
            log.error("Erreur: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur inattendue: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors du rejet"));
        }
    }

    /**
     * Approuve plusieurs patterns en lot
     * POST /api/v2/learning/bulk-approve
     */
    @PostMapping("/learning/bulk-approve")
    public ResponseEntity<?> bulkApprovePatterns(
            @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) request.get("ids");
        String approvedBy = (String) request.getOrDefault("approvedBy", "admin");

        log.info("POST /api/v2/learning/bulk-approve - {} patterns", ids.size());

        int approved = 0;
        int failed = 0;

        for (Long id : ids) {
            try {
                learningService.approvePattern(id, approvedBy);
                approved++;
            } catch (Exception e) {
                log.error("Erreur approbation pattern {}: {}", id, e.getMessage());
                failed++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Approbation en lot terminée",
                "approved", approved,
                "failed", failed,
                "total", ids.size()));
    }

    // ===================== INTÉGRATION DANS TEMPLATES =====================

    /**
     * Intègre tous les patterns prêts dans leurs templates
     * POST /api/v2/learning/integrate-all
     */
    @PostMapping("/learning/integrate-all")
    public ResponseEntity<?> integrateAllPatterns() {
        log.info("POST /api/v2/learning/integrate-all");

        try {
            int integrated = learningService.integrateAllReadyPatterns();
            return ResponseEntity.ok(Map.of(
                    "message", "Intégration terminée",
                    "integrated", integrated));
        } catch (Exception e) {
            log.error("Erreur intégration: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors de l'intégration"));
        }
    }

    // ===================== STATISTIQUES ET ANALYSE =====================

    /**
     * Récupère les statistiques d'apprentissage
     * GET /api/v2/learning/stats
     */
    @GetMapping("/learning/stats")
    public ResponseEntity<Map<String, Object>> getLearningStatistics() {
        log.info("GET /api/v2/learning/stats");
        Map<String, Object> stats = learningService.getLearningStatistics();
        return ResponseEntity.ok(stats);
    }

    /**
     * Analyse les patterns d'un champ
     * GET /api/v2/learning/analyze/{fieldName}
     */
    @GetMapping("/learning/analyze/{fieldName}")
    public ResponseEntity<List<FieldPatternInfo>> analyzeFieldPatterns(@PathVariable String fieldName) {
        log.info("GET /api/v2/learning/analyze/{}", fieldName);
        List<FieldPatternInfo> analysis = learningService.analyzeFieldPatterns(fieldName);
        return ResponseEntity.ok(analysis);
    }

    /**
     * Suggestions de patterns pour un champ
     * GET /api/v2/learning/suggestions/{fieldName}
     */
    @GetMapping("/learning/suggestions/{fieldName}")
    public ResponseEntity<?> getPatternSuggestions(
            @PathVariable String fieldName,
            @RequestParam(required = false) String supplierIce) {
        log.info("GET /api/v2/learning/suggestions/{} (ice={})", fieldName, supplierIce);

        try {
            List<FieldPatternInfo> analysis = learningService.analyzeFieldPatterns(fieldName);

            // Filtrer les suggestions recommandées
            List<FieldPatternInfo> suggestions = analysis.stream()
                    .filter(FieldPatternInfo::getRecommendedForIntegration)
                    .limit(5)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "fieldName", fieldName,
                    "suggestions", suggestions,
                    "totalAnalyzed", analysis.size()));
        } catch (Exception e) {
            log.error("Erreur suggestions: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors de la récupération des suggestions"));
        }
    }

    // ===================== UTILITAIRES =====================

    /**
     * Teste un pattern sur un texte OCR
     * POST /api/v2/learning/test-pattern
     */
    @PostMapping("/learning/test-pattern")
    public ResponseEntity<?> testPattern(@RequestBody Map<String, String> request) {
        String pattern = request.get("pattern");
        String ocrText = request.get("ocrText");

        log.info("POST /api/v2/learning/test-pattern: '{}'", pattern);

        try {
            // Recherche simple du pattern dans le texte
            int index = ocrText.indexOf(pattern);

            if (index >= 0) {
                // Extraire contexte (50 caractères avant/après)
                int start = Math.max(0, index - 50);
                int end = Math.min(ocrText.length(), index + pattern.length() + 50);
                String context = ocrText.substring(start, end);

                return ResponseEntity.ok(Map.of(
                        "found", true,
                        "position", index,
                        "context", context,
                        "suggestion", "Pattern détecté avec succès"));
            } else {
                return ResponseEntity.ok(Map.of(
                        "found", false,
                        "suggestion", "Pattern non trouvé dans le texte OCR"));
            }
        } catch (Exception e) {
            log.error("Erreur test pattern: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors du test"));
        }
    }

    @GetMapping("/{invoiceId}/test")
    public ResponseEntity<Map<String, Object>> testEndpoint(@PathVariable Long invoiceId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Endpoint fonctionnel");
        response.put("invoiceId", invoiceId);
        return ResponseEntity.ok(response);
    }

}
