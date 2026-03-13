package com.example.releve_bancaire.releve_bancaire.services;

import com.example.releve_bancaire.releve_bancaire.entity.BankStatement;
import com.example.releve_bancaire.releve_bancaire.entity.ContinuityStatus;
import com.example.releve_bancaire.releve_bancaire.repository.BankStatementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service de validation des relevés bancaires
 *
 * Vérifie :
 * - La cohérence des soldes (opening + credits - debits ≈ closing)
 * - La continuité mensuelle (closing_previous == opening_current)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BankStatementValidatorService {

    private final BankStatementRepository repository;
    private static final BigDecimal MAX_DECIMAL_15_2 = new BigDecimal("9999999999999.99");

    @Value("${banking.validation.tolerance:0.01}")
    private BigDecimal tolerance;

    /**
     * Valide la cohérence des soldes d'un relevé
     */
    public ValidationResult validateBalances(BankStatement statement) {
        log.info("Validation des soldes pour le relevé {}", statement.getId());

        ValidationResult result = new ValidationResult();
        result.statementId = statement.getId();

        // Vérifier que tous les soldes sont présents
        if (statement.getOpeningBalance() == null) {
            result.addError("Solde d'ouverture manquant");
            return result;
        }

        if (statement.getClosingBalance() == null) {
            result.addError("Solde de clôture manquant");
            return result;
        }

        if (statement.getTotalCredit() == null) {
            statement.calculateTotalsFromTransactions();
        }

        if (statement.getTotalDebit() == null) {
            statement.calculateTotalsFromTransactions();
        }

        // Calculer le solde attendu
        BigDecimal expected = statement.calculateExpectedClosingBalance();

        if (expected == null) {
            result.addError("Impossible de calculer le solde attendu");
            return result;
        }

        // Calculer la différence
        BigDecimal difference = statement.getClosingBalance().subtract(expected).abs();
        if (difference.compareTo(MAX_DECIMAL_15_2) > 0) {
            statement.setBalanceDifference(MAX_DECIMAL_15_2);
            result.addWarning("Différence de solde hors plage DECIMAL(15,2), valeur tronquée");
        } else {
            statement.setBalanceDifference(difference);
        }

        // Comparer avec la tolérance
        boolean isValid = difference.compareTo(tolerance) <= 0;
        statement.setIsBalanceValid(isValid);

        if (isValid) {
            result.balanceValid = true;
            result.addInfo(String.format(
                    "Soldes cohérents (différence: %.2f MAD, tolérance: %.2f MAD)",
                    difference, tolerance
            ));
            log.info("Soldes cohérents pour le relevé {}", statement.getId());
        } else {
            result.balanceValid = false;
            result.addError(String.format(
                    "Incohérence de solde détectée: attendu %.2f, trouvé %.2f (différence: %.2f MAD)",
                    expected, statement.getClosingBalance(), difference
            ));
            log.warn("Incohérence de solde pour le relevé {}: différence de {} MAD",
                    statement.getId(), difference);
        }

        return result;
    }

    /**
     * Vérifie la continuité mensuelle
     */
    public ContinuityResult checkContinuity(BankStatement statement) {
        log.info("Vérification de continuité pour le relevé {} ({}/{} - {})",
                statement.getId(), statement.getMonth(), statement.getYear(), statement.getRib());

        ContinuityResult result = new ContinuityResult();
        result.statementId = statement.getId();

        // Rechercher le mois précédent
        Optional<BankStatement> previousOpt = findPreviousMonth(statement);

        if (previousOpt.isEmpty()) {
            // Vérifier si c'est vraiment le premier relevé
            long countForRib = repository.countByRib(statement.getRib());

            if (countForRib == 1) {
                statement.setContinuityStatus(ContinuityStatus.FIRST_STATEMENT);
                result.continuityStatus = ContinuityStatus.FIRST_STATEMENT;
                result.addInfo("Premier relevé pour ce RIB");
                log.info("Premier relevé pour le RIB {}", statement.getRib());
            } else {
                statement.setContinuityStatus(ContinuityStatus.MISSING_PREVIOUS);
                result.continuityStatus = ContinuityStatus.MISSING_PREVIOUS;
                result.addWarning("Mois précédent manquant dans la base");
                statement.setIsContinuityValid(false);
                log.warn("Mois précédent manquant pour le relevé {}", statement.getId());
            }

            return result;
        }

        BankStatement previous = previousOpt.get();
        result.previousStatementId = previous.getId();

        // Comparer les soldes
        BigDecimal previousClosing = previous.getClosingBalance();
        BigDecimal currentOpening = statement.getOpeningBalance();

        if (previousClosing == null || currentOpening == null) {
            statement.setContinuityStatus(ContinuityStatus.INCONSISTENT_BALANCE);
            result.continuityStatus = ContinuityStatus.INCONSISTENT_BALANCE;
            result.addError("Soldes manquants pour vérification de continuité");
            statement.setIsContinuityValid(false);
            return result;
        }

        // Vérifier la cohérence
        BigDecimal difference = previousClosing.subtract(currentOpening).abs();

        if (difference.compareTo(tolerance) <= 0) {
            statement.setContinuityStatus(ContinuityStatus.CONSISTENT);
            result.continuityStatus = ContinuityStatus.CONSISTENT;
            result.addInfo(String.format(
                    "Continuité validée avec le relevé %d (différence: %.2f MAD)",
                    previous.getId(), difference
            ));
            statement.setIsContinuityValid(true);
            log.info("Continuité validée pour le relevé {}", statement.getId());
        } else {
            statement.setContinuityStatus(ContinuityStatus.INCONSISTENT_BALANCE);
            result.continuityStatus = ContinuityStatus.INCONSISTENT_BALANCE;
            result.addError(String.format(
                    "Incohérence de continuité: solde clôture précédent = %.2f, " +
                            "solde ouverture actuel = %.2f (différence: %.2f MAD)",
                    previousClosing, currentOpening, difference
            ));
            statement.setIsContinuityValid(false);
            log.warn("Incohérence de continuité pour le relevé {}: différence de {} MAD",
                    statement.getId(), difference);
        }

        return result;
    }

    /**
     * Recherche le mois précédent
     */
    private Optional<BankStatement> findPreviousMonth(BankStatement statement) {
        int prevMonth = statement.getMonth() - 1;
        int prevYear = statement.getYear();

        if (prevMonth == 0) {
            prevMonth = 12;
            prevYear = statement.getYear() - 1;
        }

        List<BankStatement> candidates = repository.findAllByRibAndYearAndMonthOrderByCreatedAtDescIdDesc(
                statement.getRib(),
                prevYear,
                prevMonth);

        return candidates.stream()
                .filter(candidate -> !candidate.getId().equals(statement.getId()))
                .findFirst();
    }

    /**
     * Validation complète d'un relevé
     */
    public FullValidationResult validateFully(BankStatement statement) {
        log.info("Validation complète du relevé {}", statement.getId());

        FullValidationResult result = new FullValidationResult();
        result.statementId = statement.getId();

        // 1. Validation des soldes
        ValidationResult balanceResult = validateBalances(statement);
        result.balanceValidation = balanceResult;

        // 2. Vérification de continuité
        ContinuityResult continuityResult = checkContinuity(statement);
        result.continuityValidation = continuityResult;

        // 3. Validation des transactions
        result.totalTransactions = statement.getTransactionCount();
        result.validTransactions = statement.getValidTransactionCount();
        result.errorTransactions = statement.getErrorTransactionCount();

        // 4. Déterminer si le relevé est globalement valide
        result.isFullyValid = balanceResult.balanceValid &&
                (continuityResult.continuityStatus == ContinuityStatus.CONSISTENT ||
                        continuityResult.continuityStatus == ContinuityStatus.FIRST_STATEMENT) &&
                statement.getErrorTransactionCount() == 0;

        // 5. Compiler les erreurs
        result.errors.addAll(balanceResult.errors);
        result.errors.addAll(continuityResult.errors);
        result.warnings.addAll(balanceResult.warnings);
        result.warnings.addAll(continuityResult.warnings);

        log.info("Validation complète terminée pour le relevé {}: valide = {}",
                statement.getId(), result.isFullyValid);

        return result;
    }

    /**
     * Résultat de validation des soldes
     */
    public static class ValidationResult {
        public Long statementId;
        public boolean balanceValid = false;
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<String> infos = new ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public void addInfo(String info) {
            infos.add(info);
        }
    }

    /**
     * Résultat de vérification de continuité
     */
    public static class ContinuityResult {
        public Long statementId;
        public Long previousStatementId;
        public ContinuityStatus continuityStatus;
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<String> infos = new ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public void addInfo(String info) {
            infos.add(info);
        }
    }

    /**
     * Résultat de validation complète
     */
    public static class FullValidationResult {
        public Long statementId;
        public boolean isFullyValid = false;
        public ValidationResult balanceValidation;
        public ContinuityResult continuityValidation;
        public int totalTransactions;
        public int validTransactions;
        public int errorTransactions;
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }
}
