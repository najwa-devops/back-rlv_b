package com.example.releve_bancaire.banking_controller;

import com.example.releve_bancaire.banking_entity.BankStatus;
import com.example.releve_bancaire.banking_entity.BankStatement;
import com.example.releve_bancaire.banking_entity.BankTransaction;
import com.example.releve_bancaire.banking_repository.BankStatementRepository;
import com.example.releve_bancaire.banking_repository.BankTransactionRepository;
import com.example.releve_bancaire.banking_services.BankTransactionAccountLearningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * API REST pour la gestion des transactions bancaires individuelles
 */
@RestController
@RequestMapping({ "/api/v2/bank-transactions", "/api/bank-transactions" })
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class BankTransactionController {

    private static final String DEFAULT_COMPTE = "349700000";

    private final BankTransactionRepository repository;
    private final BankStatementRepository statementRepository;
    private final BankTransactionAccountLearningService accountLearningService;

    // ==================== CONSULTATION ====================

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable("id") Long id) {
        return repository.findByIdWithStatement(id)
                .map(transaction -> ResponseEntity.ok(toResponse(transaction)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/statement/{statementId}")
    public ResponseEntity<?> getByStatement(@PathVariable("statementId") Long statementId) {
        List<BankTransaction> transactions = repository.findByStatementIdOrderByTransactionIndexAsc(statementId);

        List<Map<String, Object>> response = transactions.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(Map.of(
                "count", response.size(),
                "transactions", response));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(name = "statementId", required = false) Long statementId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "categorie", required = false) String categorie,
            @RequestParam(name = "sens", required = false) String sens) {
        List<BankTransaction> transactions;

        if (keyword != null && statementId != null) {
            transactions = repository.searchByLibelleForStatement(statementId, keyword);
        } else if (keyword != null) {
            transactions = repository.searchByLibelle(keyword);
        } else if (categorie != null && statementId != null) {
            transactions = repository.findByStatementIdAndCategorie(statementId, categorie);
        } else if (categorie != null) {
            transactions = repository.findByCategorie(categorie);
        } else if (sens != null && statementId != null) {
            transactions = repository.findByStatementIdAndSens(statementId, sens);
        } else if (statementId != null) {
            transactions = repository.findByStatementIdOrderByTransactionIndexAsc(statementId);
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Au moins un critère requis"));
        }

        List<Map<String, Object>> response = transactions.stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", response.size(),
                "transactions", response));
    }

    // ==================== MODIFICATION ====================

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> updates) {
        log.info("Modification transaction {}: {}", id, updates.keySet());

        return repository.findByIdWithStatement(id)
                .map(transaction -> {
                    if (!transaction.isModifiable()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", buildForbiddenTransactionMessage(
                                        transaction.getStatement() != null ? transaction.getStatement().getStatus() : null,
                                        "modification")));
                    }

                    // Mise à jour des champs autorisés
                    if (updates.containsKey("compte")) {
                        String requestedCompte = sanitizeCompte((String) updates.get("compte"));
                        transaction.setCompte(requestedCompte);
                        transaction.setIsLinked(hasSelectedCompte(requestedCompte));
                    }

                    if (updates.containsKey("isLinked")) {
                        transaction.setIsLinked((Boolean) updates.get("isLinked"));
                    }

                    if (updates.containsKey("categorie")) {
                        transaction.setCategorie((String) updates.get("categorie"));
                    }

                    if (updates.containsKey("libelle")) {
                        transaction.setLibelle((String) updates.get("libelle"));
                    }

                    if (!hasSelectedCompte(transaction.getCompte())) {
                        accountLearningService.findSuggestedAccount(transaction.getLibelle())
                                .ifPresent(suggested -> {
                                    transaction.setCompte(suggested);
                                    transaction.setIsLinked(true);
                                });
                    }
                    if (!hasSelectedCompte(transaction.getCompte())) {
                        transaction.setIsLinked(false);
                    }

                    // Appliquer les règles comptables
                    try {
                        transaction.applyAccountingRules();
                    } catch (IllegalStateException e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }

                    // Marquer le relevé comme modifié
                    if (transaction.getStatement().getStatus() == BankStatus.TREATED) {
                        transaction.getStatement().setStatus(BankStatus.TREATED);
                    }

                    BankTransaction saved = repository.save(transaction);
                    if (updates.containsKey("compte")
                            && hasSelectedCompte(saved.getCompte()) && saved.getLibelle() != null
                            && !saved.getLibelle().isBlank() && Boolean.TRUE.equals(saved.getIsLinked())) {
                        accountLearningService.learn(saved.getLibelle(), saved.getCompte());
                    }
                    return ResponseEntity.ok(toResponse(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateBankTransactionRequest request) {
        BankStatement statement = statementRepository.findById(request.getStatementId())
                .orElse(null);
        if (statement == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Relevé non trouvé"));
        }
        if (!statement.isModifiable()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", buildForbiddenTransactionMessage(statement.getStatus(), "ajout")));
        }

        Integer maxIndex = repository.findMaxTransactionIndex(statement.getId());
        int defaultIndex = (maxIndex == null ? -1 : maxIndex) + 1;
        int targetIndex = request.getTransactionIndex() != null ? request.getTransactionIndex() : defaultIndex;
        if (targetIndex < 0) {
            targetIndex = 0;
        }
        repository.shiftIndexesFrom(statement.getId(), targetIndex);

        BankTransaction tx = new BankTransaction();
        tx.setStatement(statement);
        tx.setTransactionIndex(targetIndex);
        tx.setDateOperation(request.getDateOperation());
        tx.setDateValeur(request.getDateValeur());
        tx.setLibelle(request.getLibelle());
        tx.setDebit(request.getDebit() == null ? BigDecimal.ZERO : request.getDebit());
        tx.setCredit(request.getCredit() == null ? BigDecimal.ZERO : request.getCredit());
        tx.setSens(request.getSens() == null || request.getSens().isBlank() ? "DEBIT" : request.getSens().toUpperCase());
        String compte = sanitizeCompte(request.getCompte());
        if (!hasSelectedCompte(compte)) {
            compte = accountLearningService.findSuggestedAccount(request.getLibelle()).orElse(DEFAULT_COMPTE);
        }
        tx.setCompte(compte);
        tx.setIsLinked(hasSelectedCompte(compte));
        tx.setCategorie(request.getCategorie());
        tx.setRib(statement.getRib());
        tx.setIsValid(true);
        tx.setNeedsReview(false);

        tx.applyAccountingRules();
        BankTransaction saved = repository.save(tx);
        if (hasSelectedCompte(sanitizeCompte(request.getCompte()))
                && hasSelectedCompte(saved.getCompte()) && saved.getLibelle() != null
                && !saved.getLibelle().isBlank() && Boolean.TRUE.equals(saved.getIsLinked())) {
            accountLearningService.learn(saved.getLibelle(), saved.getCompte());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @PutMapping("/bulk-update")
    public ResponseEntity<?> bulkUpdate(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) request.get("ids");

        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids requis"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> updates = (Map<String, Object>) request.get("updates");

        if (updates == null || updates.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "updates requis"));
        }

        log.info("Modification en lot de {} transactions", ids.size());

        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (Long id : ids) {
            Optional<BankTransaction> opt = repository.findByIdWithStatement(id);

            if (opt.isEmpty()) {
                errors.add("Transaction " + id + " non trouvée");
                failed++;
                continue;
            }

            BankTransaction transaction = opt.get();

            if (!transaction.isModifiable()) {
                errors.add("Transaction " + id + " non modifiable (relevé validé)");
                failed++;
                continue;
            }

            try {
                // Appliquer les mises à jour
                if (updates.containsKey("compte")) {
                    String requestedCompte = sanitizeCompte((String) updates.get("compte"));
                    transaction.setCompte(requestedCompte);
                    transaction.setIsLinked(hasSelectedCompte(requestedCompte));
                }

                if (updates.containsKey("isLinked")) {
                    transaction.setIsLinked((Boolean) updates.get("isLinked"));
                }

                if (updates.containsKey("categorie")) {
                    transaction.setCategorie((String) updates.get("categorie"));
                }

                transaction.applyAccountingRules();
                if (!hasSelectedCompte(transaction.getCompte())) {
                    transaction.setIsLinked(false);
                }
                BankTransaction saved = repository.save(transaction);
                if (updates.containsKey("compte")
                        && hasSelectedCompte(saved.getCompte()) && saved.getLibelle() != null
                        && !saved.getLibelle().isBlank() && Boolean.TRUE.equals(saved.getIsLinked())) {
                    accountLearningService.learn(saved.getLibelle(), saved.getCompte());
                }
                updated++;

            } catch (Exception e) {
                errors.add("Transaction " + id + ": " + e.getMessage());
                failed++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Modification en lot terminée",
                "updated", updated,
                "failed", failed,
                "total", ids.size(),
                "errors", errors));
    }

    // ==================== VALIDATION ====================

    @PostMapping("/{id}/mark-valid")
    public ResponseEntity<?> markValid(@PathVariable("id") Long id) {
        return repository.findByIdWithStatement(id)
                .map(transaction -> {
                    if (!transaction.isModifiable()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "Relevé validé, modification impossible"));
                    }

                    transaction.setIsValid(true);
                    transaction.setNeedsReview(false);
                    transaction.setExtractionErrors(null);

                    BankTransaction saved = repository.save(transaction);
                    return ResponseEntity.ok(toResponse(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/mark-for-review")
    public ResponseEntity<?> markForReview(
            @PathVariable("id") Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String notes = body != null ? body.get("notes") : null;

        return repository.findByIdWithStatement(id)
                .map(transaction -> {
                    if (!transaction.isModifiable()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "Relevé validé, modification impossible"));
                    }

                    transaction.setNeedsReview(true);
                    if (notes != null) {
                        transaction.setReviewNotes(notes);
                    }

                    BankTransaction saved = repository.save(transaction);
                    return ResponseEntity.ok(toResponse(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== STATISTIQUES ====================

    @GetMapping("/stats/by-categorie")
    public ResponseEntity<?> statsByCategorie(
            @RequestParam(name = "statementId", required = false) Long statementId) {
        List<Object[]> stats;

        if (statementId != null) {
            stats = repository.sumAmountsByCategorieForStatement(statementId);
        } else {
            stats = repository.getGlobalStatisticsByCategorie();
        }

        List<Map<String, Object>> response = stats.stream()
                .map(row -> Map.of(
                        "categorie", row[0],
                        "count", row[1],
                        "totalDebit", row[2] != null ? row[2] : 0,
                        "totalCredit", row[3] != null ? row[3] : 0))
                .toList();

        return ResponseEntity.ok(Map.of(
                "statementId", statementId,
                "categories", response));
    }

    @GetMapping("/stats/need-review")
    public ResponseEntity<?> needReviewStats() {
        long total = repository.count();
        long needReview = repository.findByNeedsReviewTrue().size();
        long invalid = repository.findByIsValidFalse().size();

        return ResponseEntity.ok(Map.of(
                "total", total,
                "needReview", needReview,
                "invalid", invalid,
                "reviewPercentage", total > 0 ? (needReview * 100.0 / total) : 0));
    }

    @PostMapping("/reindex/{statementId}")
    public ResponseEntity<?> reindex(@PathVariable Long statementId) {
        List<BankTransaction> txs = repository.findByStatementIdOrderByTransactionIndexAsc(statementId);
        int idx = 0;
        for (BankTransaction tx : txs) {
            tx.setTransactionIndex(idx++);
        }
        repository.saveAll(txs);
        return ResponseEntity.ok(Map.of("message", "Réindexation terminée", "statementId", statementId, "count", txs.size()));
    }

    // ==================== HELPERS ====================

    private Map<String, Object> toResponse(BankTransaction transaction) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", transaction.getId());
        response.put("statementId", transaction.getStatement() != null ? transaction.getStatement().getId() : null);
        response.put("dateOperation", transaction.getDateOperation());
        response.put("dateValeur", transaction.getDateValeur());
        response.put("libelle", transaction.getLibelle());
        response.put("rib", transaction.getRib());
        response.put("debit", transaction.getDebit());
        response.put("credit", transaction.getCredit());
        response.put("sens", transaction.getSens());
        String displayedCompte = resolveDisplayedCompte(transaction);
        response.put("compte", displayedCompte);
        response.put("compteLibelle", accountLearningService.findAccountLibelle(displayedCompte).orElse(""));
        response.put("isLinked", displayIsLinked(transaction.getIsLinked(), displayedCompte));
        response.put("categorie", transaction.getCategorie());
        response.put("role", transaction.getRole());
        response.put("extractionConfidence", transaction.getExtractionConfidence());
        response.put("isValid", transaction.getIsValid());
        response.put("needsReview", transaction.getNeedsReview());
        response.put("reviewNotes", transaction.getReviewNotes());
        response.put("extractionErrors", transaction.getExtractionErrors());
        response.put("lineNumber", transaction.getLineNumber());
        response.put("transactionIndex", transaction.getTransactionIndex());
        return response;
    }

    private String buildForbiddenTransactionMessage(BankStatus status, String action) {
        if (status == BankStatus.COMPTABILISE) {
            return "Relevé comptabilisé, " + action + " impossible";
        }
        if (status == BankStatus.VALIDATED) {
            return "Relevé validé, " + action + " impossible";
        }
        return "Relevé non modifiable, " + action + " impossible";
    }

    private boolean hasSelectedCompte(String compte) {
        return compte != null && !compte.isBlank() && !DEFAULT_COMPTE.equals(compte.trim());
    }

    private String sanitizeCompte(String compte) {
        if (compte == null) {
            return DEFAULT_COMPTE;
        }
        String sanitized = compte.trim();
        return sanitized.isBlank() ? DEFAULT_COMPTE : sanitized;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateBankTransactionRequest {
        @NotNull
        private Long statementId;
        private Integer transactionIndex;
        @NotNull
        private LocalDate dateOperation;
        private LocalDate dateValeur;
        @NotBlank
        private String libelle;
        private String compte;
        private String categorie;
        private String sens;
        private BigDecimal debit;
        private BigDecimal credit;
        private Boolean isLinked;
    }
}
