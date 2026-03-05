package com.example.releve_bancaire.servises.dynamic;

import com.example.releve_bancaire.dto.dynamic.FieldLearningDto;
import com.example.releve_bancaire.dto.dynamic.FieldPatternInfo;
import com.example.releve_bancaire.dto.dynamic.SaveFieldsWithPatternsRequest;
import com.example.releve_bancaire.dto.dynamic.UpdateDynamicTemplateRequest;
import com.example.releve_bancaire.entity.dynamic.DynamicInvoice;
import com.example.releve_bancaire.entity.dynamic.DynamicTemplate;
import com.example.releve_bancaire.entity.dynamic.FieldLearningData;
import com.example.releve_bancaire.entity.dynamic.LearningStatus;
import com.example.releve_bancaire.repository.DynamicInvoiceDao;
import com.example.releve_bancaire.repository.FieldLearningDataDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service de gestion de l'apprentissage automatique des patterns
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FieldLearningService {

    private final FieldLearningDataDao learningDao;
    private final DynamicInvoiceDao invoiceDao;
    private final DynamicTemplateService templateService;
    private final DynamicInvoiceDao dynamicInvoiceDao;

    // Seuils de confiance
    private static final double AUTO_APPROVE_THRESHOLD = 0.95;
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;
    private static final int MIN_OCCURRENCES_FOR_INTEGRATION = 3;

    // ===================== SAUVEGARDE DES PATTERNS =====================

    /**
     * Sauvegarde les patterns détectés par l'utilisateur
     */
    @Transactional
    public List<FieldLearningDto> saveFieldsWithPatterns(
            Long invoiceId,
            SaveFieldsWithPatternsRequest request) {
        log.info("Sauvegarde des patterns pour facture ID={}", invoiceId);

        DynamicInvoice invoice = invoiceDao.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Facture non trouvée: " + invoiceId));

        // Mise à jour des fieldsData de la facture
        invoice.setFieldsData(request.getFieldsData());
        invoiceDao.save(invoice);

        List<FieldLearningData> learningDataList = new ArrayList<>();

        // Pour chaque pattern détecté
        if (request.getFieldPatterns() != null) {
            request.getFieldPatterns().forEach((fieldName, pattern) -> {
                if (pattern != null && !pattern.isBlank()) {
                    FieldLearningData learning = createLearningData(
                            invoice, fieldName, pattern, request);

                    // Vérifier si un pattern similaire existe déjà
                    Optional<FieldLearningData> existing = learningDao.findByPatternHash(
                            learning.getPatternHash());

                    if (existing.isPresent()) {
                        // Incrémenter le compteur d'occurrences
                        FieldLearningData existingData = existing.get();
                        existingData.incrementOccurrence();

                        // Mettre à jour la confiance (moyenne)
                        if (learning.getConfidenceScore() != null) {
                            double newConfidence = (existingData.getConfidenceScore() +
                                    learning.getConfidenceScore()) / 2.0;
                            existingData.setConfidenceScore(newConfidence);
                        }

                        learningDataList.add(learningDao.save(existingData));
                        log.info("Pattern existant mis à jour: {} (occurrences: {})",
                                pattern, existingData.getOccurrenceCount());
                    } else {
                        // Nouveau pattern
                        FieldLearningData saved = learningDao.save(learning);
                        learningDataList.add(saved);
                        log.info("Nouveau pattern enregistré: {} pour champ {}",
                                pattern, fieldName);

                        // Auto-approuver si haute confiance
                        if (saved.isHighConfidence()) {
                            autoApproveLearning(saved.getId());
                        }
                    }
                }
            });
        }

        log.info("Total patterns enregistrés: {}", learningDataList.size());

        return learningDataList.stream()
                .map(FieldLearningDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public void savePatterns(Long invoiceId, Map<String, String> newPatterns) {
        log.info("Sauvegarde des patterns pour facture ID={}", invoiceId);

        if (newPatterns == null || newPatterns.isEmpty()) {
            log.warn("Aucun pattern à sauvegarder");
            return;
        }

        // Récupérer la facture pour contexte
        DynamicInvoice invoice = dynamicInvoiceDao.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Facture non trouvée: " + invoiceId));

        // Extraire contexte fournisseur
        String supplierIce = getStringValue(invoice.getFieldsData(), "ice");
        String supplierIf = getStringValue(invoice.getFieldsData(), "ifNumber");
        String supplierName = getStringValue(invoice.getFieldsData(), "supplier");

        int savedCount = 0;

        for (Map.Entry<String, String> entry : newPatterns.entrySet()) {
            String fieldName = entry.getKey();
            String pattern = entry.getValue();

            if (pattern == null || pattern.isBlank()) {
                log.warn("Pattern vide pour champ '{}', ignoré", fieldName);
                continue;
            }

            // Générer hash unique pour éviter doublons
            String patternHash = generatePatternHash(fieldName, pattern, supplierIce, supplierIf);

            // Vérifier si pattern existe déjà
            if (learningDao.findByPatternHash(patternHash).isPresent()) {
                log.info("Pattern déjà existant pour champ '{}', ignoré", fieldName);
                continue;
            }

            // Créer nouvelle entrée FieldLearningData
            FieldLearningData learningData = FieldLearningData.builder()
                    .invoice(invoice)
                    .fieldName(fieldName)
                    .detectedPattern(pattern)
                    .fieldValue(getStringValue(invoice.getFieldsData(), fieldName))
                    .supplierIce(supplierIce)
                    .supplierIf(supplierIf)
                    .supplierName(supplierName)
                    .patternHash(patternHash)
                    .confidenceScore(1.0) // Pattern utilisateur = confiance maximale
                    .detectionMethod("USER_MANUAL")
                    .status(LearningStatus.APPROVED) // Auto-approuvé car saisi par utilisateur
                    .createdBy("user")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            learningDao.save(learningData);
            savedCount++;
            log.info("Nouveau pattern enregistré: {} pour champ {}", pattern, fieldName);
        }

        log.info("Total patterns enregistrés: {}", savedCount);
    }

    /**
     * Crée une entité FieldLearningData à partir de la requête
     */
    private FieldLearningData createLearningData(
            DynamicInvoice invoice,
            String fieldName,
            String pattern,
            SaveFieldsWithPatternsRequest request) {
        FieldLearningData learning = FieldLearningData.builder()
                .invoice(invoice)
                .fieldName(fieldName)
                .detectedPattern(pattern)
                .fieldValue(getFieldValue(request.getFieldsData(), fieldName))
                .supplierIce(invoice.getIce())
                .supplierIf(invoice.getIfNumber())
                .supplierName(invoice.getSupplier())
                .detectionMethod(request.getDetectionMethod())
                .createdBy(request.getUserId())
                .build();

        // Positions
        if (request.getPatternPositions() != null) {
            learning.setPatternPosition(request.getPatternPositions().get(fieldName));
        }
        if (request.getValuePositions() != null) {
            learning.setValuePosition(request.getValuePositions().get(fieldName));
        }

        // Zone
        if (request.getFieldZones() != null) {
            learning.setDocumentZone(request.getFieldZones().get(fieldName));
        }

        // Contexte OCR
        if (request.getOcrContexts() != null) {
            learning.setOcrContext(request.getOcrContexts().get(fieldName));
        }

        // Confiance
        if (request.getConfidenceScores() != null && request.getConfidenceScores().containsKey(fieldName)) {
            learning.setConfidenceScore(request.getConfidenceScores().get(fieldName));
        } else {
            learning.setConfidenceScore(0.7); // Confiance par défaut
        }

        // Calculer la distance pattern-valeur
        learning.calculateDistance();

        // Générer le hash
        learning.generatePatternHash();

        return learning;
    }

    private String getFieldValue(Map<String, Object> fieldsData, String fieldName) {
        Object value = fieldsData.get(fieldName);
        return value != null ? value.toString() : null;
    }

    // ===================== GESTION DES PATTERNS =====================

    /**
     * Récupère tous les patterns en attente de validation
     */
    @Transactional(readOnly = true)
    public List<FieldLearningDto> getPendingPatterns() {
        log.info("Récupération des patterns en attente");
        return learningDao.findByStatusOrderByCreatedAtDesc(LearningStatus.PENDING).stream()
                .map(FieldLearningDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Récupère les patterns d'une facture
     */
    @Transactional(readOnly = true)
    public List<FieldLearningDto> getPatternsByInvoice(Long invoiceId) {
        log.info("Récupération des patterns pour facture ID={}", invoiceId);
        return learningDao.findByInvoiceId(invoiceId).stream()
                .map(FieldLearningDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Récupère les patterns d'un fournisseur
     */
    @Transactional(readOnly = true)
    public List<FieldLearningDto> getPatternsBySupplier(String supplierIce) {
        log.info("Récupération des patterns pour fournisseur ICE={}", supplierIce);
        return learningDao.findBySupplierIceAndStatusOrderByCreatedAtDesc(
                supplierIce, LearningStatus.APPROVED).stream()
                .map(FieldLearningDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Approuve un pattern manuellement
     */
    @Transactional
    public FieldLearningDto approvePattern(Long learningId, String approvedBy) {
        log.info("Approbation du pattern ID={} par {}", learningId, approvedBy);

        FieldLearningData learning = learningDao.findById(learningId)
                .orElseThrow(() -> new IllegalArgumentException("Pattern non trouvé: " + learningId));

        learning.approve(approvedBy);
        FieldLearningData saved = learningDao.save(learning);

        log.info("Pattern approuvé: {}", learning.getDetectedPattern());

        // Vérifier si prêt pour intégration
        checkAndIntegratePattern(saved);

        return FieldLearningDto.fromEntity(saved);
    }

    /**
     * Auto-approuve un pattern (haute confiance)
     */
    @Transactional
    public void autoApproveLearning(Long learningId) {
        log.info("Auto-approbation du pattern ID={}", learningId);

        FieldLearningData learning = learningDao.findById(learningId)
                .orElseThrow(() -> new IllegalArgumentException("Pattern non trouvé: " + learningId));

        if (learning.getConfidenceScore() >= AUTO_APPROVE_THRESHOLD) {
            learning.autoApprove();
            FieldLearningData saved = learningDao.save(learning);
            log.info("Pattern auto-approuvé: {}", learning.getDetectedPattern());

            checkAndIntegratePattern(saved);
        }
    }

    /**
     * Rejette un pattern
     */
    @Transactional
    public FieldLearningDto rejectPattern(Long learningId, String rejectedBy, String reason) {
        log.info("Rejet du pattern ID={} par {}", learningId, rejectedBy);

        FieldLearningData learning = learningDao.findById(learningId)
                .orElseThrow(() -> new IllegalArgumentException("Pattern non trouvé: " + learningId));

        learning.reject(rejectedBy, reason);
        FieldLearningData saved = learningDao.save(learning);

        log.info("Pattern rejeté: {} (raison: {})", learning.getDetectedPattern(), reason);

        return FieldLearningDto.fromEntity(saved);
    }

    // ===================== INTÉGRATION DANS TEMPLATES =====================

    /**
     * Vérifie et intègre un pattern dans un template si les conditions sont
     * remplies
     */
    @Transactional
    public void checkAndIntegratePattern(FieldLearningData learning) {
        if (!learning.isReadyForIntegration()) {
            log.debug("Pattern {} pas encore prêt pour l'intégration", learning.getId());
            return;
        }

        // Vérifier le nombre d'occurrences
        if (learning.getOccurrenceCount() < MIN_OCCURRENCES_FOR_INTEGRATION) {
            log.debug("Pattern {} nécessite plus d'occurrences ({}/{})",
                    learning.getId(), learning.getOccurrenceCount(), MIN_OCCURRENCES_FOR_INTEGRATION);
            return;
        }

        // Chercher le template correspondant
        Optional<DynamicTemplate> templateOpt;
        if (learning.getSupplierIce() != null) {
            templateOpt = templateService.findByIce(learning.getSupplierIce());
        } else if (learning.getSupplierIf() != null) {
            templateOpt = templateService.findByIfNumber(learning.getSupplierIf());
        } else {
            log.warn("Pattern {} sans ICE ni IF, impossible d'intégrer", learning.getId());
            return;
        }

        if (templateOpt.isEmpty()) {
            log.info("Aucun template trouvé pour le pattern {}, création automatique nécessaire",
                    learning.getId());
            // TODO: Créer automatiquement le template
            return;
        }

        DynamicTemplate template = templateOpt.get();
        integratePatternIntoTemplate(learning, template);
    }

    /**
     * Intègre un pattern dans un template
     */
    @Transactional
    public void integratePatternIntoTemplate(FieldLearningData learning, DynamicTemplate template) {
        log.info("Intégration du pattern '{}' dans template ID={}",
                learning.getDetectedPattern(), template.getId());

        // Vérifier si le champ existe déjà dans le template
        DynamicTemplate.DynamicFieldDefinitionJson existingField = template.getFieldDefinition(learning.getFieldName());

        if (existingField != null) {
            // Ajouter le pattern aux labels existants
            List<String> labels = new ArrayList<>(existingField.getLabels());
            if (!labels.contains(learning.getDetectedPattern())) {
                labels.add(learning.getDetectedPattern());
                existingField.setLabels(labels);
                log.info("Pattern ajouté aux labels existants du champ {}", learning.getFieldName());
            }
        } else {
            // Créer une nouvelle définition de champ
            DynamicTemplate.DynamicFieldDefinitionJson newField = DynamicTemplate.DynamicFieldDefinitionJson.builder()
                    .fieldName(learning.getFieldName())
                    .labels(List.of(learning.getDetectedPattern()))
                    .fieldType("TEXT")
                    .detectionMethod("LABEL_BASED")
                    .required(false)
                    .confidenceThreshold(0.7)
                    .searchZone(learning.getDocumentZone() != null ? learning.getDocumentZone() : "ALL")
                    .build();

            List<DynamicTemplate.DynamicFieldDefinitionJson> fields = new ArrayList<>(template.getFieldDefinitions());
            fields.add(newField);
            template.setFieldDefinitions(fields);

            log.info("Nouveau champ {} créé dans le template", learning.getFieldName());
        }

        // Sauvegarder le template
        templateService.patchTemplate(template.getId(),
                createUpdateRequestFromTemplate(template));

        // Marquer comme appliqué
        learning.markAsApplied(template.getId());
        learningDao.save(learning);

        log.info("Pattern intégré avec succès dans le template");
    }

    /**
     * Intègre tous les patterns prêts
     */
    @Transactional
    public int integrateAllReadyPatterns() {
        log.info("Intégration de tous les patterns prêts");

        List<FieldLearningData> readyPatterns = learningDao.findReadyForIntegration();
        int integrated = 0;

        for (FieldLearningData learning : readyPatterns) {
            try {
                checkAndIntegratePattern(learning);
                integrated++;
            } catch (Exception e) {
                log.error("Erreur lors de l'intégration du pattern ID={}: {}",
                        learning.getId(), e.getMessage());
            }
        }

        log.info("Intégration terminée: {} patterns intégrés", integrated);
        return integrated;
    }

    // ===================== STATISTIQUES ET ANALYSE =====================

    /**
     * Récupère les statistiques d'apprentissage
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getLearningStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("totalPatterns", learningDao.count());
        stats.put("pending", learningDao.countByStatus(LearningStatus.PENDING));
        stats.put("approved", learningDao.countByStatus(LearningStatus.APPROVED));
        stats.put("rejected", learningDao.countByStatus(LearningStatus.REJECTED));
        stats.put("autoApproved", learningDao.countByStatus(LearningStatus.AUTO_APPROVED));

        List<FieldLearningData> readyForIntegration = learningDao.findReadyForIntegration();
        stats.put("readyForIntegration", readyForIntegration.size());

        List<FieldLearningData> highConfidence = learningDao.findHighConfidencePending(HIGH_CONFIDENCE_THRESHOLD);
        stats.put("highConfidencePending", highConfidence.size());

        return stats;
    }

    /**
     * Analyse les patterns d'un champ
     */
    @Transactional(readOnly = true)
    public List<FieldPatternInfo> analyzeFieldPatterns(String fieldName) {
        log.info("Analyse des patterns pour le champ: {}", fieldName);

        List<FieldLearningData> patterns = learningDao.findByFieldNameAndStatusOrderByOccurrenceCountDesc(
                fieldName, LearningStatus.APPROVED);

        Map<String, List<FieldLearningData>> groupedByPattern = patterns.stream()
                .collect(Collectors.groupingBy(FieldLearningData::getDetectedPattern));

        return groupedByPattern.entrySet().stream()
                .map(entry -> {
                    String pattern = entry.getKey();
                    List<FieldLearningData> data = entry.getValue();

                    double avgConfidence = data.stream()
                            .mapToDouble(d -> d.getConfidenceScore() != null ? d.getConfidenceScore() : 0.0)
                            .average()
                            .orElse(0.0);

                    int totalOccurrences = data.stream()
                            .mapToInt(d -> d.getOccurrenceCount() != null ? d.getOccurrenceCount() : 0)
                            .sum();

                    return FieldPatternInfo.builder()
                            .fieldName(fieldName)
                            .pattern(pattern)
                            .occurrenceCount(totalOccurrences)
                            .averageConfidence(avgConfidence)
                            .recommendedForIntegration(
                                    totalOccurrences >= MIN_OCCURRENCES_FOR_INTEGRATION &&
                                            avgConfidence >= HIGH_CONFIDENCE_THRESHOLD)
                            .build();
                })
                .sorted(Comparator.comparingInt(FieldPatternInfo::getOccurrenceCount).reversed())
                .collect(Collectors.toList());
    }

    // ===================== HELPERS =====================

    private UpdateDynamicTemplateRequest createUpdateRequestFromTemplate(DynamicTemplate template) {
        // Conversion simplifiée - à adapter selon vos besoins
        return UpdateDynamicTemplateRequest.builder()
                .templateName(template.getTemplateName())
                .supplierType(template.getSupplierType())
                .description(template.getDescription())
                .build();
    }

    private String generatePatternHash(String fieldName, String pattern, String ice, String ifNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append(fieldName).append(":");
        sb.append(pattern.trim().toLowerCase()).append(":");

        // Ajouter ICE ou IF pour lier au fournisseur
        if (ice != null && !ice.isBlank()) {
            sb.append(ice);
        } else if (ifNumber != null && !ifNumber.isBlank()) {
            sb.append(ifNumber);
        }

        // Hash simple (vous pouvez utiliser MD5/SHA si besoin)
        return Integer.toHexString(sb.toString().hashCode());
    }

    private String getStringValue(Map<String, Object> map, String key) {
        if (map == null)
            return null;
        Object value = map.get(key);
        if (value == null)
            return null;
        return value.toString().trim();
    }
}
