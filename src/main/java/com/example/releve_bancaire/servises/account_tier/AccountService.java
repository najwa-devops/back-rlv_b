package com.example.releve_bancaire.servises.account_tier;

import com.example.releve_bancaire.account_tier.Account;
import com.example.releve_bancaire.dto.account_tier.AccountDto;
import com.example.releve_bancaire.dto.account_tier.CreateAccountRequest;
import com.example.releve_bancaire.dto.account_tier.UpdateAccountRequest;
import com.example.releve_bancaire.repository.AccountDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final AccountDao accountDao;

    // ===================== CRUD =====================

    /**
     * Crée un nouveau compte
     * @param request Données du compte à créer
     * @return DTO du compte créé
     * @throws IllegalArgumentException si le code existe déjà
     */
    @Transactional
    public AccountDto createAccount(CreateAccountRequest request) {
        log.info("Creation compte: code={}, libelle={}", request.getCode(), request.getLibelle());
        request.validate();

        // Validation unicite du code
        if (accountDao.existsByCode(request.getCode())) {
            throw new IllegalArgumentException("Un compte avec le code " + request.getCode() + " existe deja");
        }

        Integer classe = deriveClasseFromCode(request.getCode());

        // Creation de l'entite
        Account account = Account.builder()
                .code(request.getCode())
                .libelle(request.getLibelle())
                .classe(classe)
                .tvaRate(request.getTvaRate())
                .active(request.getActive() != null ? request.getActive() : true)
                .createdBy(request.getCreatedBy())
                .build();

        Account saved = accountDao.save(account);

        log.info("Compte cree: ID={}, code={}, classe={}",
                saved.getId(), saved.getCode(), saved.getClasse());

        return AccountDto.fromEntity(saved);
    }

    private Integer deriveClasseFromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Le code du compte est obligatoire");
        }

        char firstChar = code.charAt(0);
        if (!Character.isDigit(firstChar)) {
            throw new IllegalArgumentException(
                    "Code compte invalide: il doit commencer par un chiffre (1-8). Recu: " + code
            );
        }

        int classe = Character.getNumericValue(firstChar);
        if (classe < 1 || classe > 8) {
            throw new IllegalArgumentException(
                    "Code compte invalide: la classe doit etre entre 1 et 8. Recu: " + code
            );
        }

        return classe;
    }

    /**
     * Met à jour un compte existant
     * @param id ID du compte
     * @param request Nouvelles données
     * @return DTO du compte mis à jour
     * @throws IllegalArgumentException si le compte n'existe pas
     */
    @Transactional
    public AccountDto updateAccount(Long id, UpdateAccountRequest request) {
        log.info("Mise à jour compte ID={}", id);

        Account account = accountDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Compte non trouvé: " + id));

        // Mise à jour des champs modifiables
        if (request.getLibelle() != null && !request.getLibelle().isBlank()) {
            account.setLibelle(request.getLibelle());
            log.debug("  Libellé mis à jour: {}", request.getLibelle());
        }

        if (request.getActive() != null) {
            account.setActive(request.getActive());
            log.debug("  Statut actif mis à jour: {}", request.getActive());
        }

        if (request.getUpdatedBy() != null) {
            account.setUpdatedBy(request.getUpdatedBy());
        }

        Account saved = accountDao.save(account);
        log.info("Compte mis à jour: ID={}, code={}", saved.getId(), saved.getCode());

        return AccountDto.fromEntity(saved);
    }

    /**
     * Récupère un compte par son ID
     * @param id ID du compte
     * @return DTO du compte
     */
    @Transactional(readOnly = true)
    public Optional<AccountDto> getAccountById(Long id) {
        return accountDao.findById(id)
                .map(AccountDto::fromEntity);
    }

    /**
     * Récupère un compte par son code
     * @param code Code du compte
     * @return DTO du compte
     */
    @Transactional(readOnly = true)
    public Optional<AccountDto> getAccountByCode(String code) {
        return accountDao.findByCode(code)
                .map(AccountDto::fromEntity);
    }

    /**
     * Récupère tous les comptes actifs, triés par code
     * @return Liste des comptes actifs
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getAllActiveAccounts() {
        try {
            return accountDao.findByActiveTrueOrderByCodeAsc().stream()
                    .map(AccountDto::fromEntity)
                    .collect(Collectors.toList());
        } catch (DataAccessException e) {
            log.error("Lecture comptes actifs impossible: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Récupère tous les comptes (actifs et inactifs), triés par code
     * @return Liste de tous les comptes
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getAllAccounts() {
        try {
            return accountDao.findAllByOrderByCodeAsc().stream()
                    .map(AccountDto::fromEntity)
                    .collect(Collectors.toList());
        } catch (DataAccessException e) {
            log.error("Lecture de tous les comptes impossible: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Désactive un compte (soft delete)
     * @param id ID du compte
     * @throws IllegalArgumentException si le compte n'existe pas
     */
    @Transactional
    public void deactivateAccount(Long id) {
        log.info("Désactivation compte ID={}", id);

        Account account = accountDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Compte non trouvé: " + id));

        account.deactivate();
        accountDao.save(account);

        log.info("Compte désactivé: ID={}, code={}", account.getId(), account.getCode());
    }

    /**
     * Réactive un compte
     * @param id ID du compte
     * @throws IllegalArgumentException si le compte n'existe pas
     */
    @Transactional
    public void activateAccount(Long id) {
        log.info("Activation compte ID={}", id);

        Account account = accountDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Compte non trouvé: " + id));

        account.activate();
        accountDao.save(account);

        log.info("Compte activé: ID={}, code={}", account.getId(), account.getCode());
    }

    // ===================== RECHERCHE PAR CLASSE =====================

    /**
     * Récupère tous les comptes d'une classe donnée
     * @param classe Numéro de classe (1-8)
     * @return Liste des comptes de cette classe
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getAccountsByClasse(Integer classe) {
        log.debug("Recherche comptes classe {}", classe);

        if (classe == null || classe < 1 || classe > 8) {
            throw new IllegalArgumentException("La classe doit être entre 1 et 8");
        }

        return accountDao.findByClasseAndActiveTrue(classe).stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    // ===================== COMPTES SPÉCIFIQUES =====================

    /**
     * Récupère tous les comptes fournisseurs (441XXX)
     * @return Liste des comptes fournisseurs actifs
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getFournisseurAccounts() {
        log.debug("Recherche comptes fournisseurs (441XXX)");

        return accountDao.findFournisseurAccounts().stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Récupère tous les comptes de charge (classe 6)
     * @return Liste des comptes de charge actifs
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getChargeAccounts() {
        log.debug("Recherche comptes de charge (classe 6)");

        return accountDao.findChargeAccounts().stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Récupère tous les comptes TVA (3455XX)
     * @return Liste des comptes TVA actifs
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getTvaAccounts() {
        log.debug("Recherche comptes TVA (3455XX)");

        return accountDao.findTvaAccounts().stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    // ===================== RECHERCHE =====================

    /**
     * Recherche des comptes par code ou libellé
     * @param query Texte de recherche
     * @return Liste des comptes correspondants (actifs uniquement)
     */
    @Transactional(readOnly = true)
    public List<AccountDto> searchAccounts(String query) {
        log.debug("Recherche comptes: query={}", query);

        if (query == null || query.isBlank()) {
            return getAllActiveAccounts();
        }

        return accountDao.searchActive(query).stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    // ===================== STATISTIQUES =====================

    /**
     * Récupère les statistiques du plan comptable
     * @return Map contenant les statistiques
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStatistics() {
        log.debug("Récupération statistiques plan comptable");

        long totalAccounts = accountDao.count();
        long activeAccounts = accountDao.countByActiveTrue();

        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalAccounts", totalAccounts);
        stats.put("activeAccounts", activeAccounts);
        stats.put("inactiveAccounts", totalAccounts - activeAccounts);

        // Statistiques par classe
        Map<Integer, Long> byClasse = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) {
            long count = accountDao.countByClasse(i);
            if (count > 0) {
                byClasse.put(i, count);
            }
        }
        stats.put("byClasse", byClasse);

        // Comptes spéciaux
        stats.put("fournisseurAccounts", accountDao.findFournisseurAccounts().size());
        stats.put("chargeAccounts", accountDao.findChargeAccounts().size());
        stats.put("tvaAccounts", accountDao.findTvaAccounts().size());

        return stats;
    }

    // ===================== IMPORT EN MASSE =====================

    /**
     * Importe une liste de comptes en masse
     * @param requests Liste de comptes à créer
     * @return Liste des comptes créés
     */
    @Transactional
    public List<AccountDto> importAccounts(List<CreateAccountRequest> requests) {
        log.info("Import en masse de {} comptes", requests.size());

        List<AccountDto> created = new java.util.ArrayList<>();
        int successCount = 0;
        int errorCount = 0;

        for (CreateAccountRequest request : requests) {
            try {
                // Vérifier si le compte existe déjà
                if (accountDao.existsByCode(request.getCode())) {
                    log.warn("Compte {} déjà existant, ignoré", request.getCode());
                    errorCount++;
                    continue;
                }

                AccountDto account = createAccount(request);
                created.add(account);
                successCount++;

            } catch (Exception e) {
                log.error("Erreur import compte {}: {}", request.getCode(), e.getMessage());
                errorCount++;
            }
        }

        log.info("Import terminé: {} succès, {} erreurs", successCount, errorCount);

        return created;
    }

    // ===================== VALIDATION =====================

    /**
     * Valide qu'un code de compte existe
     * @param code Code du compte
     * @return true si le compte existe
     */
    @Transactional(readOnly = true)
    public boolean accountExists(String code) {
        return accountDao.existsByCode(code);
    }

    /**
     * Valide qu'un code de compte existe et est actif
     * @param code Code du compte
     * @return true si le compte existe et est actif
     */
    @Transactional(readOnly = true)
    public boolean accountExistsAndActive(String code) {
        return accountDao.findByCode(code)
                .map(Account::getActive)
                .orElse(false);
    }

    /**
     * Valide le format d'un code de compte
     * @param code Code à valider
     * @return true si le format est valide (4-10 chiffres)
     */
    public boolean isValidAccountCode(String code) {
        return code != null && code.matches("^\\d{4,10}$");
    }

    /**
     * Valide qu'un compte fournisseur est valide (format 441XXX)
     * @param code Code du compte
     * @return true si le format est valide
     */
    public boolean isValidFournisseurAccount(String code) {
        return code != null && code.matches("^441\\d{3,7}$");
    }
}
