package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.banking_entity.BankTransactionAccountRule;
import com.example.releve_bancaire.banking_repository.BankTransactionAccountRuleRepository;
import com.example.releve_bancaire.account_tier.Account;
import com.example.releve_bancaire.repository.AccountDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BankTransactionAccountLearningService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9 ]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern ACCOUNT_CODE_9_DIGITS = Pattern.compile("^\\d{9}$");
    private static final Pattern ACCOUNT_CODE_8_DIGITS = Pattern.compile("^\\d{8}$");
    private static final Set<String> STOP_WORDS = Set.of(
            "DE", "DU", "DES", "LA", "LE", "LES", "ET", "EN", "AU", "AUX", "SUR", "PAR", "POUR",
            "VIR", "VIREMENT", "PAIEMENT", "PRELEVEMENT", "OPERATION", "CB", "WEB", "REF", "RECU", "EMIS");

    private final BankTransactionAccountRuleRepository repository;
    private final AccountDao accountDao;

    @Transactional
    public void learn(String libelle, String accountCode) {
        String normalized = normalize(libelle);
        String sanitizedCode = sanitizeAccountCode(accountCode);
        if (normalized.isBlank() || !isValidExistingAccount(sanitizedCode)) {
            return;
        }

        BankTransactionAccountRule rule = repository.findByNormalizedLibelle(normalized)
                .orElseGet(() -> BankTransactionAccountRule.builder()
                        .normalizedLibelle(normalized)
                        .usageCount(0)
                        .build());

        rule.setAccountCode(sanitizedCode);
        rule.setExampleLibelle(libelle);
        rule.setUsageCount((rule.getUsageCount() == null ? 0 : rule.getUsageCount()) + 1);

        repository.save(rule);
    }

    @Transactional(readOnly = true)
    public Optional<String> findSuggestedAccount(String libelle) {
        String normalized = normalize(libelle);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        Optional<BankTransactionAccountRule> exact = repository.findByNormalizedLibelle(normalized);
        if (exact.isPresent()) {
            String candidate = sanitizeAccountCode(exact.get().getAccountCode());
            return isValidExistingAccount(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        List<BankTransactionAccountRule> rules = repository.findTop200ByOrderByUsageCountDescUpdatedAtDesc();
        double bestScore = 0.0;
        String bestAccount = null;

        for (BankTransactionAccountRule rule : rules) {
            String candidate = rule.getNormalizedLibelle();
            if (candidate == null || candidate.isBlank()) {
                continue;
            }

            double score = prefixSimilarityScore(normalized, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestAccount = sanitizeAccountCode(rule.getAccountCode());
            }
        }

        if (bestScore >= 0.8 && isValidExistingAccount(bestAccount)) {
            return Optional.ofNullable(bestAccount);
        }

        return findSuggestedAccountFromPlan(normalized);
    }

    @Transactional(readOnly = true)
    public Optional<String> findAccountLibelle(String accountCode) {
        String code = sanitizeAccountCode(accountCode);
        if (!isPotentialAccountCode(code)) {
            return Optional.empty();
        }
        return accountDao.findByCode(code)
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .map(Account::getLibelle);
    }

    @Transactional(readOnly = true)
    public Map<String, String> findAccountLibelles(Collection<String> accountCodes) {
        if (accountCodes == null || accountCodes.isEmpty()) {
            return Map.of();
        }
        Set<String> sanitizedCodes = accountCodes.stream()
                .map(this::sanitizeAccountCode)
                .filter(this::isPotentialAccountCode)
                .collect(Collectors.toSet());
        if (sanitizedCodes.isEmpty()) {
            return Map.of();
        }

        List<Account> accounts = accountDao.findByCodeInAndActiveTrue(sanitizedCodes);
        Map<String, String> labels = new LinkedHashMap<>();
        for (Account account : accounts) {
            if (account.getCode() != null && account.getLibelle() != null) {
                labels.put(account.getCode().trim(), account.getLibelle());
            }
        }
        return labels;
    }

    // Matching voulu par métier:
    // - identique complet, ou
    // - grande partie identique depuis le début (préfixe),
    // - jamais un match "au milieu".
    private double prefixSimilarityScore(String src, String dst) {
        if (src == null || dst == null || src.isBlank() || dst.isBlank()) {
            return 0.0;
        }

        if (!(src.startsWith(dst) || dst.startsWith(src))) {
            return 0.0;
        }

        int prefixLength = commonPrefixLength(src, dst);
        if (prefixLength < 15) {
            return 0.0;
        }

        int minLength = Math.min(src.length(), dst.length());
        return minLength == 0 ? 0.0 : (double) prefixLength / minLength;
    }

    private int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int idx = 0;
        while (idx < max && a.charAt(idx) == b.charAt(idx)) {
            idx++;
        }
        return idx;
    }

    private String normalize(String libelle) {
        if (libelle == null) {
            return "";
        }
        String value = Normalizer.normalize(libelle, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase();
        value = NON_ALNUM.matcher(value).replaceAll(" ");
        value = MULTI_SPACE.matcher(value).replaceAll(" ").trim();
        return value;
    }

    private Optional<String> findSuggestedAccountFromPlan(String normalizedLibelle) {
        List<Account> accounts = accountDao.findByActiveTrueOrderByCodeAsc();
        if (accounts.isEmpty()) {
            return Optional.empty();
        }

        List<String> txTokens = meaningfulTokens(normalizedLibelle);
        double bestScore = 0.0;
        String bestCode = null;

        for (Account account : accounts) {
            String code = sanitizeAccountCode(account.getCode());
            if (!isPotentialAccountCode(code)) {
                continue;
            }

            String normalizedAccountLibelle = normalize(account.getLibelle());
            if (normalizedAccountLibelle.isBlank()) {
                continue;
            }

            double score = libelleSimilarityScore(normalizedLibelle, txTokens, normalizedAccountLibelle);
            if (score > bestScore) {
                bestScore = score;
                bestCode = code;
            }
        }

        return bestScore >= 0.55 ? Optional.ofNullable(bestCode) : Optional.empty();
    }

    private double libelleSimilarityScore(String normalizedTx, List<String> txTokens, String normalizedAccount) {
        if (normalizedTx.equals(normalizedAccount)) {
            return 1.0;
        }
        if (normalizedTx.contains(normalizedAccount) || normalizedAccount.contains(normalizedTx)) {
            return 0.9;
        }

        List<String> accountTokens = meaningfulTokens(normalizedAccount);
        if (accountTokens.isEmpty() || txTokens.isEmpty()) {
            return 0.0;
        }

        int shared = 0;
        for (String token : accountTokens) {
            if (txTokens.contains(token)) {
                shared++;
            }
        }

        if (shared == 0) {
            return 0.0;
        }

        double recall = (double) shared / accountTokens.size();
        double precision = (double) shared / txTokens.size();
        return (0.7 * recall) + (0.3 * precision);
    }

    private List<String> meaningfulTokens(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(normalizedValue.split(" "))
                .map(String::trim)
                .filter(token -> token.length() >= 4)
                .filter(token -> !STOP_WORDS.contains(token))
                .distinct()
                .toList();
    }

    private String sanitizeAccountCode(String accountCode) {
        if (accountCode == null) {
            return "";
        }
        String code = accountCode.trim();
        if (ACCOUNT_CODE_8_DIGITS.matcher(code).matches()) {
            String padded = code + "0";
            if (accountDao.existsByCode(padded)) {
                return padded;
            }
        }
        return code;
    }

    private boolean isValidExistingAccount(String code) {
        return code != null
                && ACCOUNT_CODE_9_DIGITS.matcher(code).matches()
                && accountDao.existsByCode(code);
    }

    private boolean isPotentialAccountCode(String code) {
        return code != null && ACCOUNT_CODE_9_DIGITS.matcher(code).matches();
    }
}
