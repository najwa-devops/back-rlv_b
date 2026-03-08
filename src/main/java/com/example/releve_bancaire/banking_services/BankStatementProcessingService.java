package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.banking_entity.BankStatement;
import com.example.releve_bancaire.banking_entity.BankStatus;
import com.example.releve_bancaire.banking_entity.BankTransaction;
import com.example.releve_bancaire.banking_repository.BankStatementRepository;
import com.example.releve_bancaire.banking_repository.BankTransactionRepository;
import com.example.releve_bancaire.banking_services.banking_ocr.OcrCleaningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ✅ SERVICE TRAITEMENT RELEVÉS BANCAIRES - VERSION
 * 
 * Utilise TransactionExtractorService pour une extraction précise
 * basée sur les structures spécifiques de chaque banque
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BankStatementProcessingService {

    private static final String DEFAULT_COMPTE = "349700000";
    private static final BigDecimal TTC_DIVISOR = new BigDecimal("1.1");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String COMMISSION_LABEL = "SOUS COMMISSION";

    private final BankStatementProcessor bankStatementProcessor;
    private final OcrCleaningService cleaningService;
    private final MetadataExtractorService metadataExtractor;
    private final TransactionExtractorService transactionExtractor; // ✅ VERSION 2
    private final BankTransactionAccountLearningService accountLearningService;
    private final BankStatementValidatorService validator;
    private final BankStatementRepository statementRepository;
    private final BankTransactionRepository transactionRepository;

    @Value("${banking.duplicate-detection-enabled:false}")
    private boolean duplicateDetectionEnabled;
    @Value("${banking.storage.temporary-file-retention:true}")
    private boolean temporaryFileRetention;

    private BankStatementProcessingService self;

    @Autowired
    public void setSelf(@Lazy BankStatementProcessingService self) {
        this.self = self;
    }

    @Async("bankingTaskExecutor")
    public void processStatementAsync(Long statementId, String bankType, List<String> allowedBanks) {
        log.info("🚀 [ASYNC] Début tâche pour relevé ID: {} (Banque forcée: {}, Autorisées: {})", statementId,
                bankType, allowedBanks);
        try {
            self.processStatement(statementId, bankType, allowedBanks);
        } catch (Exception e) {
            log.error("❌ [ASYNC] Erreur critique lors du traitement {}: {}",
                    statementId, e.getMessage(), e);

            // Marquer le relevé en erreur
            try {
                self.markAsError(statementId, "Erreur traitement: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    @Async("bankingTaskExecutor")
    public void reprocessStatementAsync(Long statementId) {
        log.info("🔄 [ASYNC] Retraitement pour relevé ID: {}", statementId);
        try {
            self.reprocessStatement(statementId, null);
        } catch (Exception e) {
            log.error("❌ [ASYNC] Erreur critique lors du retraitement {}: {}",
                    statementId, e.getMessage(), e);
        }
    }

    @Async("bankingTaskExecutor")
    public void reprocessStatementAsync(Long statementId, List<String> allowedBanks) {
        log.info("🔄 [ASYNC] Retraitement pour relevé ID: {} (Autorisées: {})", statementId, allowedBanks);
        try {
            self.reprocessStatement(statementId, allowedBanks);
        } catch (Exception e) {
            log.error("❌ [ASYNC] Erreur critique lors du retraitement {}: {}",
                    statementId, e.getMessage(), e);
        }
    }

    @Transactional
    public void markAsError(Long statementId, String errorMessage) {
        statementRepository.findById(statementId).ifPresent(stmt -> {
            stmt.setStatus(BankStatus.ERROR);
            stmt.setValidationErrors(errorMessage);
            statementRepository.save(stmt);
        });
    }

    @Transactional
    public BankStatement processStatement(Long statementId, String bankType, List<String> allowedBanks) {
        BankStatement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new RuntimeException("Relevé non trouvé: " + statementId));
        return processStatement(statement, bankType, allowedBanks);
    }

    @Transactional
    public BankStatement processStatement(BankStatement statement, String bankType, List<String> allowedBanks) {
        log.info("=== DÉBUT TRAITEMENT RELEVÉ {} ===", statement.getId());
        // Règle métier: conserver tous les uploads, même identiques.
        final boolean duplicateDetectionActive = false;
        if (duplicateDetectionEnabled) {
            log.warn("La détection de doublon est configurée mais neutralisée (chaque relevé est conservé).");
        }

        try {
            statement.setStatus(BankStatus.PROCESSING);
            statement = statementRepository.save(statement);

            // 1. Extraction via Processor Bancaire
            log.info("📄 Étape 1/7: Extraction texte OCR");
            String rawOcrText = performExtraction(statement);
            statement.setRawOcrText(rawOcrText);

            log.debug("Texte brut extrait: {} caractères", rawOcrText.length());
            if (log.isTraceEnabled()) {
                log.trace("Aperçu OCR brut:\n{}", rawOcrText.substring(0, Math.min(500, rawOcrText.length())));
            }

            // 2. Nettoyage
            log.info("🧹 Étape 2/7: Nettoyage texte");
            String cleanedText = cleaningService.cleanOcrText(rawOcrText);
            statement.setCleanedOcrText(cleanedText);

            log.debug("Texte nettoyé: {} caractères", cleanedText.length());

            // 3. Extraction métadonnées
            log.info("🔍 Étape 3/7: Extraction métadonnées");
            var metadata = metadataExtractor.extractMetadata(cleanedText);

            if (duplicateDetectionActive) {
                // Vérification doublon métier AVANT d'appliquer (rib,year,month) pour éviter
                // la violation de contrainte unique et le rollback complet.
                DuplicateKey duplicateKey = resolveDuplicateKey(statement, metadata);
                Optional<BankStatement> duplicateTarget = findDuplicateByPeriod(statement, duplicateKey);
                if (duplicateTarget.isPresent()) {
                    markAsDuplicateAndSave(statement, metadata, duplicateTarget.get());
                    log.info("=== FIN TRAITEMENT - Statut: DUPLIQUE (relevé déjà existant) ===");
                    return statement;
                }
            }

            log.info("Métadonnées extraites - RIB: {}, Période: {}/{}, Banque: {}",
                    statement.getRib(), statement.getMonth(), statement.getYear(), statement.getBankName());

            // 3.5 Validation de la politique de banque autorisée
            applyMetadata(statement, metadata);
            if (allowedBanks != null && !allowedBanks.isEmpty()) {
                boolean isAutoAllowed = allowedBanks.contains("AUTO");
                String detectedType = metadata.bankType != null ? metadata.bankType.name() : "UNKNOWN";
                boolean isSpecificAllowed = allowedBanks.contains(detectedType);

                // Si AUTO est coché, on accepte tout. Sinon, seulement les banques cochées.
                boolean finalAllowed = isAutoAllowed || isSpecificAllowed;

                log.info(
                        "🔍 Validation politique - Détecté: [{}], AUTO autorisé: {}, Spécifique autorisé: {}, Résultat: {}",
                        detectedType, isAutoAllowed, isSpecificAllowed, finalAllowed);
                log.info("📋 Liste des types autorisés reçus: {}", allowedBanks);
                for (String allowed : allowedBanks) {
                    log.debug("   - Comparaison avec [{}]: match={}", allowed, allowed.equals(detectedType));
                }

                if (!finalAllowed) {
                    log.error("❌ Politique de banque violée: {} n'est pas autorisé (Choix: {})", detectedType,
                            allowedBanks);
                    String detectedBank = statement.getBankName() != null ? statement.getBankName() : "Inconnue";
                    markAsError(statement.getId(),
                            "Structure non autorisée: " + detectedBank
                                    + ". Ajoutez cette banque dans la liste des banques autorisées puis lancez le reprocessing.");
                    return statement;
                }
            }

            // 4. Gestion doublons (RIB + période + totaux PDF)
            if (duplicateDetectionActive) {
                markAsDuplicateIfNeeded(statement);
            } else {
                clearDuplicateFlags(statement);
            }

            // 5. Extraction transactions
            log.info("💳 Étape 4/7: Extraction transactions ()");
            List<BankTransaction> transactions = transactionExtractor.extractTransactions(
                    cleanedText,
                    statement.getMonth(),
                    statement.getYear(),
                    bankType);
            transactions = applyTtcCommissionSplitIfEnabled(statement, transactions);

            log.info("✅ {} transactions extraites", transactions.size());

            // Vérification critique
            if (transactions.isEmpty()) {
                log.warn("⚠️ AUCUNE TRANSACTION EXTRAITE - Vérifier le format du relevé");
                log.debug("Aperçu du texte nettoyé pour débug:\n{}",
                        cleanedText.length() > 2000 ? cleanedText.substring(0, 2000) : cleanedText);
            }

            // 6. Associer transactions au statement
            log.info("🔗 Étape 5/7: Association transactions");
            for (BankTransaction transaction : transactions) {
                // Priorité 1: suggestion issue de l'apprentissage utilisateur.
                // Fallback obligatoire: compte par défaut.
                String suggested = accountLearningService.findSuggestedAccount(transaction.getLibelle())
                        .orElse(DEFAULT_COMPTE);
                transaction.setCompte(suggested);
                transaction.setIsLinked(!DEFAULT_COMPTE.equals(suggested));

                statement.addTransaction(transaction);
                transaction.setStatement(statement);
                transaction.setRib(statement.getRib());
            }
            reindexTransactions(transactions);

            // Période du relevé: priorité à la période metadata (header).
            // Fallback uniquement si metadata absente.
            List<LocalDate> operationDates = transactions.stream()
                    .map(BankTransaction::getDateOperation)
                    .filter(Objects::nonNull)
                    .toList();
            if (!operationDates.isEmpty()) {
                LocalDate minDate = operationDates.stream().min(LocalDate::compareTo).orElse(null);
                if (minDate != null && (statement.getMonth() == null || statement.getYear() == null)) {
                    statement.setMonth(minDate.getMonthValue());
                    statement.setYear(minDate.getYear());
                }
            }

            // 7. Calculs comptables
            log.info("🧮 Étape 6/7: Calculs totaux");
            calculateTotals(statement);
            statement.updateTransactionCounters();
            applyVerification(statement);

            log.info("Totaux calculés - Crédit: {}, Débit: {}, Nombre: {}",
                    statement.getTotalCredit(),
                    statement.getTotalDebit(),
                    statement.getTransactionCount());

            // 8. Confiance OCR moyenne
            double avgConfidence = calculateAverageConfidence(transactions);
            statement.setOverallConfidence(avgConfidence);
            log.debug("Confiance moyenne: {}", avgConfidence);

            // 9. Validation
            log.info("✅ Étape 7/7: Validation");
            var balanceValidation = validator.validateBalances(statement);
            var continuityValidation = validator.checkContinuity(statement);

            // 10. Déterminer statut final
            determineStatus(statement, balanceValidation, continuityValidation);
            if (isEmptyStatement(statement)) {
                statement.setValidationErrors(null);
            } else if (transactions.isEmpty()) {
                String snippet = cleanedText.length() > 200 ? cleanedText.substring(0, 200) : cleanedText;
                statement.setValidationErrors(
                        "⚠️ Zéro transactions extraites. Aperçu OCR: " + snippet.replace("\n", " | "));
            } else {
                compileValidationErrors(statement, balanceValidation, continuityValidation);
            }

            // Stockage temporaire: supprimer le binaire source après extraction réussie.
            purgeUploadedBinaryIfConfigured(statement);

            // 10. Sauvegarde finale
            log.info("💾 Sauvegarde finale");
            BankStatement saved = statementRepository.save(statement);

            // Sauvegarde explicite des transactions
            if (!transactions.isEmpty()) {
                log.info("💾 Sauvegarde de {} transactions", transactions.size());
                transactionRepository.saveAll(transactions);
                log.info("✅ Transactions sauvegardées avec succès");
            }

            log.info("=== ✅ FIN TRAITEMENT - Statut: {} | Transactions: {} ===",
                    saved.getStatus(), saved.getTransactionCount());

            return saved;

        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKeyViolation(e)) {
                if (!duplicateDetectionActive) {
                    log.error("Conflit unique RIB/période détecté alors que les doublons sont autorisés. " +
                            "Supprimez l'index unique uk_rib_year_month sur bank_statement.");
                    statement.setStatus(BankStatus.ERROR);
                    statement.setValidationErrors(
                            "Contrainte DB unique (rib,year,month) active: supprimez uk_rib_year_month");
                    return statementRepository.save(statement);
                }
                statement.setIsDuplicate(true);
                statement.setStatus(BankStatus.DUPLIQUE);
                statement.setValidationErrors("Doublon DB: même RIB/période déjà présent en base");
                statementRepository.save(statement);
                log.warn("Doublon DB intercepté pour relevé {}: {}", statement.getId(), e.getMessage());
                return statement;
            }
            log.error("❌ Erreur traitement: {}", e.getMessage(), e);
            statement.setStatus(BankStatus.ERROR);
            statement.setValidationErrors("Erreur: " + e.getMessage());
            statementRepository.save(statement);
            throw new RuntimeException("Échec traitement: " + e.getMessage(), e);
        } catch (Exception e) {
            if (isDuplicateKeyViolation(e) && !duplicateDetectionActive) {
                log.error("Conflit unique RIB/période détecté alors que les doublons sont autorisés. " +
                        "Supprimez l'index unique uk_rib_year_month sur bank_statement.");
                statement.setStatus(BankStatus.ERROR);
                statement.setValidationErrors(
                        "Contrainte DB unique (rib,year,month) active: supprimez uk_rib_year_month");
                return statementRepository.saveAndFlush(statement);
            }
            log.error("❌ Erreur traitement: {}", e.getMessage(), e);
            statement.setStatus(BankStatus.ERROR);
            statement.setValidationErrors("Erreur: " + e.getMessage());
            statementRepository.save(statement);
            throw new RuntimeException("Échec traitement: " + e.getMessage(), e);
        }
    }

    private List<BankTransaction> applyTtcCommissionSplitIfEnabled(BankStatement statement, List<BankTransaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return txs;
        }
        if (!Boolean.TRUE.equals(statement.getApplyTtcRule())) {
            return txs;
        }

        List<BankTransaction> expanded = new ArrayList<>();
        for (BankTransaction tx : txs) {
            if (!containsCommission(tx.getLibelle())) {
                expanded.add(tx);
                continue;
            }

            BigDecimal originalAmount = transactionAmount(tx);
            if (originalAmount == null || originalAmount.compareTo(ZERO) <= 0) {
                expanded.add(tx);
                continue;
            }

            BigDecimal amountHt = originalAmount.divide(TTC_DIVISOR, 2, RoundingMode.HALF_UP);
            BigDecimal amountTax = originalAmount.subtract(amountHt).setScale(2, RoundingMode.HALF_UP);

            tx.setDebit(isDebit(tx) ? amountHt : ZERO);
            tx.setCredit(isDebit(tx) ? ZERO : amountHt);
            expanded.add(tx);

            BankTransaction taxTx = cloneForSplit(tx);
            taxTx.setLibelle(COMMISSION_LABEL);
            taxTx.setDebit(isDebit(tx) ? amountTax : ZERO);
            taxTx.setCredit(isDebit(tx) ? ZERO : amountTax);
            expanded.add(taxTx);
        }
        return expanded;
    }

    private boolean containsCommission(String libelle) {
        if (libelle == null || libelle.isBlank()) {
            return false;
        }
        String normalized = java.text.Normalizer.normalize(libelle, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase();
        if (Pattern.compile("\\b(SOUS|SUR)\\s+COMM?ISSION\\b").matcher(normalized).find()) {
            return false;
        }
        return Pattern.compile("\\bCOMM?ISSION\\b").matcher(normalized).find();
    }

    private boolean isDebit(BankTransaction tx) {
        return tx.getDebit() != null && tx.getDebit().compareTo(ZERO) > 0;
    }

    private BigDecimal transactionAmount(BankTransaction tx) {
        if (tx.getDebit() != null && tx.getDebit().compareTo(ZERO) > 0) {
            return tx.getDebit();
        }
        if (tx.getCredit() != null && tx.getCredit().compareTo(ZERO) > 0) {
            return tx.getCredit();
        }
        return ZERO;
    }

    private BankTransaction cloneForSplit(BankTransaction source) {
        BankTransaction clone = new BankTransaction();
        clone.setDateOperation(source.getDateOperation());
        clone.setDateValeur(source.getDateValeur());
        clone.setRib(source.getRib());
        clone.setReference(source.getReference());
        clone.setCode(source.getCode());
        clone.setSens(source.getSens());
        clone.setCompte(source.getCompte());
        clone.setIsLinked(source.getIsLinked());
        clone.setCategorie(source.getCategorie());
        clone.setRole(source.getRole());
        clone.setExtractionConfidence(source.getExtractionConfidence());
        clone.setIsValid(source.getIsValid());
        clone.setNeedsReview(source.getNeedsReview());
        clone.setExtractionErrors(source.getExtractionErrors());
        clone.setLineNumber(source.getLineNumber());
        clone.setRawOcrLinePath(source.getRawOcrLinePath());
        clone.setRawOcrLine(source.getRawOcrLine());
        clone.setReviewNotes(source.getReviewNotes());
        return clone;
    }

    private void reindexTransactions(List<BankTransaction> transactions) {
        for (int i = 0; i < transactions.size(); i++) {
            transactions.get(i).setTransactionIndex(i + 1);
        }
    }

    private String performExtraction(BankStatement statement) {
        byte[] fileData = statement.getFileData();
        if (fileData != null && fileData.length > 0) {
            String sourceName = statement.getOriginalName() != null ? statement.getOriginalName() : statement.getFilename();
            log.info("Extraction du fichier en base: {} ({} bytes)", sourceName, fileData.length);

            String extractedText = bankStatementProcessor.process(fileData, sourceName);
            if (extractedText == null || extractedText.trim().isEmpty()) {
                throw new RuntimeException("Le processeur n'a extrait aucun texte du fichier");
            }

            log.info("✅ Extraction terminée: {} caractères", extractedText.length());
            return extractedText;
        }

        String filePath = statement.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new RuntimeException("Fichier introuvable (aucun BLOB et filePath vide). id=" + statement.getId()
                    + ", filename=" + statement.getFilename());
        }
        Path sourcePath = Paths.get(filePath).toAbsolutePath().normalize();
        if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            throw new RuntimeException("Fichier introuvable sur disque: " + sourcePath);
        }
        log.info("Extraction du fichier sur disque: {}", sourcePath);
        String extractedText = bankStatementProcessor.process(sourcePath.toFile());
        if (extractedText == null || extractedText.trim().isEmpty()) {
            throw new RuntimeException("Le processeur n'a extrait aucun texte du fichier");
        }

        log.info("✅ Extraction terminée depuis disque: {} caractères", extractedText.length());
        return extractedText;
    }

    private void applyMetadata(BankStatement statement, MetadataExtractorService.BankStatementMetadata metadata) {
        if (metadata.rib != null) {
            statement.setRib(metadata.rib);
            log.debug("RIB: {}", metadata.rib);
        }

        if (metadata.month != null && metadata.month > 0 && metadata.month <= 12) {
            statement.setMonth(metadata.month);
            log.debug("Mois: {}", metadata.month);
        }

        if (metadata.year != null && metadata.year > 2000 && metadata.year <= 2100) {
            statement.setYear(metadata.year);
            log.debug("Année: {}", metadata.year);
        }

        if (metadata.totalDebitPdf != null) {
            statement.setTotalDebitPdf(metadata.totalDebitPdf);
            log.debug("Total débit PDF: {}", metadata.totalDebitPdf);
        }

        if (metadata.totalCreditPdf != null) {
            statement.setTotalCreditPdf(metadata.totalCreditPdf);
            log.debug("Total crédit PDF: {}", metadata.totalCreditPdf);
        }

        if (metadata.openingBalance != null) {
            statement.setOpeningBalance(metadata.openingBalance);
            log.debug("Solde ouverture: {}", metadata.openingBalance);
        }

        if (metadata.closingBalance != null) {
            statement.setClosingBalance(metadata.closingBalance);
            log.debug("Solde clôture: {}", metadata.closingBalance);
        }

        if (metadata.bankName != null) {
            statement.setBankName(metadata.bankName);
            log.debug("Banque: {}", metadata.bankName);
        }

        if (metadata.accountHolder != null) {
            statement.setAccountHolder(metadata.accountHolder);
            log.debug("Titulaire: {}", metadata.accountHolder);
        }
    }

    private void calculateTotals(BankStatement statement) {
        statement.calculateTotalsFromTransactions();
        log.debug("Totaux calculés - Crédit: {}, Débit: {}",
                statement.getTotalCredit(), statement.getTotalDebit());
    }

    private void purgeUploadedBinaryIfConfigured(BankStatement statement) {
        if (!temporaryFileRetention || statement == null || statement.getFileData() == null) {
            return;
        }

        statement.setFileData(null);
        statement.setFilePath("TEMP_CLEARED");
        log.info("🗑️ Fichier binaire supprimé (mode temporaire) pour relevé {}", statement.getId());
    }

    private void applyVerification(BankStatement statement) {
        BigDecimal debitPdf = statement.getTotalDebitPdf();
        BigDecimal creditPdf = statement.getTotalCreditPdf();
        BigDecimal debitCalc = statement.getTotalDebit() != null ? statement.getTotalDebit() : BigDecimal.ZERO;
        BigDecimal creditCalc = statement.getTotalCredit() != null ? statement.getTotalCredit() : BigDecimal.ZERO;

        if (debitPdf == null && creditPdf == null) {
            statement.setVerificationStatus(null);
            return;
        }

        boolean debitOk = debitPdf == null || debitPdf.subtract(debitCalc).abs().compareTo(new BigDecimal("0.01")) <= 0;
        boolean creditOk = creditPdf == null
                || creditPdf.subtract(creditCalc).abs().compareTo(new BigDecimal("0.01")) <= 0;

        statement.setVerificationStatus(debitOk && creditOk ? "OK" : "INCOHERENCE");
    }

    private double calculateAverageConfidence(List<BankTransaction> transactions) {
        return transactions.stream()
                .mapToDouble(t -> t.getExtractionConfidence() != null ? t.getExtractionConfidence() : 0.5)
                .average()
                .orElse(0.5);
    }

    private void markAsDuplicateIfNeeded(BankStatement statement) {
        String hash = buildDuplicateHash(statement);
        statement.setDuplicateHash(hash);
        statement.setIsDuplicate(false);

        if (hash == null || hash.isBlank()) {
            return;
        }

        Optional<BankStatement> existing = findFirstOtherByDuplicateHash(statement, hash);
        if (existing.isPresent()) {
            statement.setIsDuplicate(true);
            statement.setValidationErrors("Doublon detecte");
        }
    }

    private void clearDuplicateFlags(BankStatement statement) {
        statement.setIsDuplicate(false);
        statement.setDuplicateHash(null);
        if (statement.getValidationErrors() != null && statement.getValidationErrors().startsWith("DUPLIQUE_OF:")) {
            statement.setValidationErrors(null);
        }
    }

    private Optional<BankStatement> findDuplicateByPeriod(BankStatement statement, DuplicateKey key) {
        if (key == null || key.rib == null || key.month == null || key.year == null) {
            return Optional.empty();
        }

        return findFirstOtherByPeriod(statement, key.rib, key.year, key.month);
    }

    private Optional<BankStatement> findFirstOtherByPeriod(BankStatement statement, String rib, Integer year,
            Integer month) {
        if (rib == null || year == null || month == null) {
            return Optional.empty();
        }
        return statementRepository.findAllByRibAndYearAndMonthOrderByCreatedAtDescIdDesc(rib, year, month)
                .stream()
                .filter(candidate -> !candidate.getId().equals(statement.getId()))
                .findFirst();
    }

    private Optional<BankStatement> findFirstOtherByDuplicateHash(BankStatement statement, String duplicateHash) {
        if (duplicateHash == null || duplicateHash.isBlank()) {
            return Optional.empty();
        }
        return statementRepository.findAllByDuplicateHashOrderByCreatedAtDescIdDesc(duplicateHash)
                .stream()
                .filter(candidate -> !candidate.getId().equals(statement.getId()))
                .findFirst();
    }

    private void markAsDuplicateAndSave(BankStatement statement,
            MetadataExtractorService.BankStatementMetadata metadata,
            BankStatement existing) {
        if (metadata != null) {
            if (metadata.bankName != null) {
                statement.setBankName(metadata.bankName);
            }
            if (metadata.totalDebitPdf != null) {
                statement.setTotalDebitPdf(metadata.totalDebitPdf);
            }
            if (metadata.totalCreditPdf != null) {
                statement.setTotalCreditPdf(metadata.totalCreditPdf);
            }
        }
        statement.setIsDuplicate(true);
        statement.setStatus(BankStatus.DUPLIQUE);
        statement.setValidationErrors("DUPLIQUE_OF:" + existing.getId()
                + "; Doublon: même RIB/période déjà présent en base");
        statement.setDuplicateHash(existing.getDuplicateHash());
        statementRepository.save(statement);
    }

    private DuplicateKey resolveDuplicateKey(BankStatement statement,
            MetadataExtractorService.BankStatementMetadata metadata) {
        DuplicateKey key = new DuplicateKey();
        key.rib = metadata != null && metadata.rib != null ? metadata.rib : statement.getRib();
        key.month = metadata != null && metadata.month != null ? metadata.month : statement.getMonth();
        key.year = metadata != null && metadata.year != null ? metadata.year : statement.getYear();
        return key;
    }

    private boolean isDuplicateKeyViolation(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && (msg.contains("Duplicate entry") || msg.contains("duplicate key")
                    || msg.contains("UKgwgu01850vgaj7qi537rgvm43"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static class DuplicateKey {
        private String rib;
        private Integer month;
        private Integer year;
    }

    private String buildDuplicateHash(BankStatement statement) {
        if (statement.getRib() == null || statement.getMonth() == null || statement.getYear() == null) {
            return null;
        }

        String debit = statement.getTotalDebitPdf() != null
                ? statement.getTotalDebitPdf().toPlainString()
                : "0.0";
        String credit = statement.getTotalCreditPdf() != null
                ? statement.getTotalCreditPdf().toPlainString()
                : "0.0";

        String period = String.format("%02d/%04d", statement.getMonth(), statement.getYear());
        String raw = String.join("|", statement.getRib(), period, debit, credit);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isEmptyStatement(BankStatement statement) {
        boolean noRib = statement.getRib() == null || statement.getRib().isBlank();
        boolean noTotals = statement.getTotalDebitPdf() == null && statement.getTotalCreditPdf() == null;
        boolean noTransactions = statement.getTransactionCount() == 0;
        return noRib && noTotals && noTransactions;
    }

    private void determineStatus(BankStatement statement, Object balanceValidation, Object continuityValidation) {
        if (isEmptyStatement(statement)) {
            statement.setStatus(BankStatus.TREATED);
            statement.setValidationErrors("VIDE");
            log.info("Statut TREATED (VIDE): fichier vide");
            return;
        }

        if (Boolean.TRUE.equals(statement.getIsDuplicate())) {
            statement.setStatus(BankStatus.TREATED);
            log.info("Statut TREATED (DUPLIQUE): doublon détecté");
            return;
        }

        if (statement.getTransactionCount() == 0) {
            statement.setStatus(BankStatus.ERROR);
            log.warn("Statut ERROR: Aucune transaction");
            return;
        }

        if ("INCOHERENCE".equals(statement.getVerificationStatus())) {
            statement.setStatus(BankStatus.TREATED);
            log.info("Statut TREATED: incohérence comptable");
            return;
        }

        statement.setStatus(BankStatus.READY_TO_VALIDATE);
        log.info("Statut READY_TO_VALIDATE");
    }

    private void compileValidationErrors(BankStatement statement, Object balanceValidation,
            Object continuityValidation) {
        List<String> errors = new ArrayList<>();
        if (!errors.isEmpty()) {
            statement.setValidationErrors(String.join("; ", errors));
        }
    }

    @Transactional
    public BankStatement reprocessStatement(Long statementId) {
        return reprocessStatement(statementId, null);
    }

    @Transactional
    public BankStatement reprocessStatement(Long statementId, List<String> allowedBanks) {
        log.info("🔄 Retraitement du relevé ID: {}", statementId);

        BankStatement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new IllegalArgumentException("Relevé non trouvé: " + statementId));

        if (!statement.isModifiable()) {
            throw new IllegalStateException("Relevé validé, retraitement impossible");
        }

        // Supprimer les transactions existantes
        log.info("Suppression des {} transactions existantes", statement.getTransactionCount());
        transactionRepository.deleteByStatementId(statementId);
        statement.getTransactions().clear();

        // Réinitialiser le statut
        statement.setStatus(BankStatus.PENDING);
        statement.setValidationErrors(null);

        // Retraiter
        return processStatement(statement, null, allowedBanks);
    }

    @Transactional
    public void deleteStatement(Long id) {
        log.info("🗑️ Suppression du relevé ID: {}", id);

        BankStatement statement = statementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Relevé non trouvé: " + id));

        // Supprimer les transactions
        transactionRepository.deleteByStatementId(id);

        if (statement.getTransactions() != null) {
            statement.getTransactions().clear();
        }

        // Supprimer le relevé
        statementRepository.delete(statement);

        log.info("✅ Relevé {} supprimé avec succès", id);
    }

    public ProcessingStatistics getStatistics() {
        ProcessingStatistics stats = new ProcessingStatistics();
        stats.totalStatements = statementRepository.count();
        stats.pendingStatements = statementRepository.countByStatus(BankStatus.PENDING);
        stats.processingStatements = statementRepository.countByStatus(BankStatus.PROCESSING);
        stats.treatedStatements = statementRepository.countByStatus(BankStatus.TREATED);
        stats.readyStatements = statementRepository.countByStatus(BankStatus.READY_TO_VALIDATE);
        stats.validatedStatements = statementRepository.countByStatus(BankStatus.VALIDATED);
        stats.accountedStatements = statementRepository.countByStatus(BankStatus.COMPTABILISE);
        stats.errorStatements = statementRepository.countByStatus(BankStatus.ERROR);
        stats.totalRibs = statementRepository.countDistinctRibs();
        return stats;
    }

    public static class ProcessingStatistics {
        public long totalStatements;
        public long pendingStatements;
        public long processingStatements;
        public long treatedStatements;
        public long readyStatements;
        public long validatedStatements;
        public long accountedStatements;
        public long errorStatements;
        public long totalRibs;
        public long invalidStatements;
        public Double averageConfidence;
    }
}
