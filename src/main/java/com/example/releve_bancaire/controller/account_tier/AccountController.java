package com.example.releve_bancaire.controller.account_tier;

import com.example.releve_bancaire.account_tier.Compte;
import com.example.releve_bancaire.banking_services.ExternalComptesCatalogService;
import com.example.releve_bancaire.dto.account_tier.AccountDto;
import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.dto.account_tier.CreateAccountRequest;
import com.example.releve_bancaire.dto.account_tier.UpdateAccountRequest;
import com.example.releve_bancaire.repository.CompteDao;
import com.example.releve_bancaire.servises.account_tier.AccountService;
import com.example.releve_bancaire.security.RequireRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/accounting/accounts")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
@RequireRole({ UserRole.ADMIN })
public class AccountController {

    private final AccountService accountService;
    private final ExternalComptesCatalogService externalComptesCatalogService;
    private final CompteDao compteDao;

    // ===================== CRÉATION =====================
    @PostMapping
    public ResponseEntity<Map<String, Object>> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        log.info("tierNumberPOST /api/accounting/accounts - Création compte: {}", request.getCode());

        try {
            AccountDto account = accountService.createAccount(request);

            log.info("tierNumberCompte créé: ID={}, Code={}", account.getId(), account.getCode());

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "message", "Compte créé avec succès",
                    "account", account));

        } catch (IllegalArgumentException e) {
            log.error("tierNumberErreur validation: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()));

        } catch (Exception e) {
            log.error("tierNumberErreur création compte: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Erreur lors de la création du compte"));
        }
    }

    // ===================== LECTURE =====================
    @GetMapping("/{numero}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> getAccountById(@PathVariable String numero) {
        log.debug("tierNumberGET /api/accounting/accounts/{}", numero);

        Optional<AccountDto> accountOpt = accountService.getAccountById(numero);

        if (accountOpt.isPresent()) {
            return ResponseEntity.ok(Map.of("account", accountOpt.get()));
        } else {
            Map<String, Object> errorResponse = Map.of(
                    "error", "Compte non trouvé avec le numero: " + numero);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }

    @GetMapping("/by-code/{code}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> getAccountByCode(@PathVariable String code) {
        log.debug("tierNumberGET /api/accounting/accounts/by-code/{}", code);

        Optional<AccountDto> accountOpt = accountService.getAccountByCode(code);

        if (accountOpt.isPresent()) {
            return ResponseEntity.ok(Map.of("account", accountOpt.get()));
        } else {
            Map<String, Object> errorResponse = Map.of(
                    "error", "Compte non trouvé avec le code: " + code);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }
    }

    @GetMapping
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getAllAccounts(
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String query) {
        log.debug("tierNumberGET /api/accounting/accounts (page={}, size={}, query={})", page, size, query);
        try {
            List<Compte> allComptes;
            
            // Try external database first
            try {
                List<Compte> externalComptes = externalComptesCatalogService.loadAccounts();
                
                // If external is empty, fallback to local comptes table
                if (externalComptes.isEmpty()) {
                    log.info("External comptes empty, falling back to local comptes table");
                    externalComptes = activeOnly 
                        ? compteDao.findByActiveTrueOrderByNumeroAsc()
                        : compteDao.findAllByOrderByNumeroAsc();
                }
                allComptes = externalComptes;
            } catch (Exception e) {
                log.warn("External comptes failed, falling back to local table: {}", e.getMessage());
                // Fallback to local table on error
                allComptes = activeOnly 
                    ? compteDao.findByActiveTrueOrderByNumeroAsc()
                    : compteDao.findAllByOrderByNumeroAsc();
            }
            
            // Apply search filter if query provided
            List<Compte> filteredComptes = allComptes;
            if (query != null && !query.isBlank()) {
                String searchQuery = query.toLowerCase().trim();
                filteredComptes = allComptes.stream()
                    .filter(c -> c.getNumero().toLowerCase().contains(searchQuery)
                            || c.getLibelle().toLowerCase().contains(searchQuery))
                    .toList();
            }
            
            // Apply pagination
            int total = filteredComptes.size();
            int fromIndex = Math.min(page * size, total);
            int toIndex = Math.min(fromIndex + size, total);
            List<Compte> pagedComptes = filteredComptes.subList(fromIndex, toIndex);
            
            List<AccountDto> accounts = pagedComptes.stream()
                    .map(AccountDto::fromEntity)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "count", accounts.size(),
                    "total", total,
                    "page", page,
                    "size", size,
                    "totalPages", (int) Math.ceil((double) total / size),
                    "accounts", accounts));
        } catch (Exception e) {
            log.error("tierNumberErreur lecture comptes: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "count", 0,
                    "total", 0,
                    "page", page,
                    "size", size,
                    "accounts", List.of()));
        }
    }

    // ===================== RECHERCHE =====================
    @GetMapping("/search")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> searchAccounts(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        log.debug("tierNumberGET /api/accounting/accounts/search?query={}, page={}, size={}", query, page, size);
        
        try {
            List<Compte> allComptes;
            
            // Try external database first
            try {
                List<Compte> externalComptes = externalComptesCatalogService.loadAccounts();
                if (externalComptes.isEmpty()) {
                    log.info("External comptes empty, falling back to local comptes table");
                    externalComptes = compteDao.findByActiveTrueOrderByNumeroAsc();
                }
                allComptes = externalComptes;
            } catch (Exception e) {
                log.warn("External comptes failed, falling back to local table: {}", e.getMessage());
                allComptes = compteDao.findByActiveTrueOrderByNumeroAsc();
            }
            
            // Apply search filter
            String searchQuery = query != null ? query.toLowerCase().trim() : "";
            List<Compte> filteredComptes = allComptes.stream()
                .filter(c -> c.getNumero().toLowerCase().contains(searchQuery)
                        || c.getLibelle().toLowerCase().contains(searchQuery))
                .toList();
            
            // Apply pagination
            int total = filteredComptes.size();
            int fromIndex = Math.min(page * size, total);
            int toIndex = Math.min(fromIndex + size, total);
            List<Compte> pagedComptes = filteredComptes.subList(fromIndex, toIndex);
            
            List<AccountDto> accounts = pagedComptes.stream()
                    .map(AccountDto::fromEntity)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "query", query,
                    "count", accounts.size(),
                    "total", total,
                    "page", page,
                    "size", size,
                    "totalPages", (int) Math.ceil((double) total / size),
                    "accounts", accounts));
        } catch (Exception e) {
            log.error("tierNumberErreur recherche comptes: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "query", query,
                    "count", 0,
                    "total", 0,
                    "accounts", List.of()));
        }
    }

    @GetMapping("/by-classe/{classe}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getAccountsByClasse(@PathVariable Integer classe) {
        log.debug("tierNumberGET /api/accounting/accounts/by-classe/{}", classe);

        try {
            List<AccountDto> accounts = externalComptesCatalogService.loadAccounts().stream()
                    .filter(account -> account.getClasse() != null && account.getClasse().equals(classe))
                    .map(AccountDto::fromEntity)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "classe", classe,
                    "count", accounts.size(),
                    "accounts", accounts));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()));
        }
    }

    // ===================== COMPTES SPÉCIFIQUES =====================
    @GetMapping("/fournisseurs")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getFournisseurAccounts() {
        log.debug("tierNumberGET /api/accounting/accounts/fournisseurs");

        List<AccountDto> accounts = externalComptesCatalogService.loadAccounts().stream()
                .filter(account -> account.getNumero() != null && account.getNumero().startsWith("441"))
                .map(AccountDto::fromEntity)
                .toList();

        return ResponseEntity.ok(Map.of(
                "type", "fournisseurs",
                "count", accounts.size(),
                "accounts", accounts));
    }

    @GetMapping("/charges")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getChargeAccounts() {
        log.debug("tierNumberGET /api/accounting/accounts/charges");

        List<AccountDto> accounts = externalComptesCatalogService.loadAccounts().stream()
                .filter(account -> account.getClasse() != null && account.getClasse() == 6)
                .map(AccountDto::fromEntity)
                .toList();

        return ResponseEntity.ok(Map.of(
                "type", "charges",
                "count", accounts.size(),
                "accounts", accounts));
    }

    @GetMapping("/tva")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getTvaAccounts() {
        log.debug("tierNumberGET /api/accounting/accounts/tva");

        List<AccountDto> accounts = externalComptesCatalogService.loadAccounts().stream()
                .filter(account -> account.getClasse() != null && account.getClasse() == 3
                        && account.getNumero() != null && account.getNumero().startsWith("3455"))
                .map(AccountDto::fromEntity)
                .toList();

        return ResponseEntity.ok(Map.of(
                "type", "tva",
                "count", accounts.size(),
                "accounts", accounts));
    }

    // ===================== MISE À JOUR =====================
    @PutMapping("/{numero}")
    public ResponseEntity<Map<String, Object>> updateAccount(
            @PathVariable String numero,
            @Valid @RequestBody UpdateAccountRequest request) {
        log.info("tierNumberPUT /api/accounting/accounts/{}", numero);

        try {
            AccountDto account = accountService.updateAccount(numero, request);

            log.info("tierNumberCompte mis à jour: numero={}", account.getCode());

            return ResponseEntity.ok(Map.of(
                    "message", "Compte mis à jour avec succès",
                    "account", account));

        } catch (IllegalArgumentException e) {
            log.error("tierNumberErreur: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", e.getMessage()));

        } catch (Exception e) {
            log.error("tierNumberErreur mise à jour: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Erreur lors de la mise à jour"));
        }
    }

    // ===================== SUPPRESSION (SOFT DELETE) =====================
    @DeleteMapping("/{numero}")
    public ResponseEntity<Map<String, Object>> deactivateAccount(@PathVariable String numero) {
        log.info("tierNumberDELETE /api/accounting/accounts/{}", numero);

        try {
            accountService.deactivateAccount(numero);

            log.info("tierNumberCompte désactivé: numero={}", numero);

            return ResponseEntity.ok(Map.of(
                    "message", "Compte désactivé avec succès",
                    "accountNumero", numero));

        } catch (IllegalArgumentException e) {
            log.error("tierNumberErreur: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", e.getMessage()));

        } catch (Exception e) {
            log.error("tierNumberErreur désactivation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Erreur lors de la désactivation"));
        }
    }

    @PatchMapping("/{numero}/activate")
    public ResponseEntity<Map<String, Object>> activateAccount(@PathVariable String numero) {
        log.info("tierNumberPATCH /api/accounting/accounts/{}/activate", numero);

        try {
            accountService.activateAccount(numero);

            log.info("tierNumberCompte réactivé: numero={}", numero);

            return ResponseEntity.ok(Map.of(
                    "message", "Compte réactivé avec succès",
                    "accountNumero", numero));

        } catch (IllegalArgumentException e) {
            log.error("tierNumberErreur: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", e.getMessage()));

        } catch (Exception e) {
            log.error("tierNumberErreur réactivation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Erreur lors de la réactivation"));
        }
    }

    // ===================== STATISTIQUES =====================
    @GetMapping("/stats")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getStatistics() {
        log.debug("tierNumberGET /api/accounting/accounts/stats");

        Map<String, Object> stats = accountService.getStatistics();

        return ResponseEntity.ok(stats);
    }

    // ===================== IMPORT EN MASSE =====================
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importAccounts(
            @Valid @RequestBody List<CreateAccountRequest> requests) {
        log.info("tierNumberPOST /api/accounting/accounts/import - {} comptes", requests.size());

        try {
            List<AccountDto> imported = accountService.importAccounts(requests);

            log.info("tierNumberImport terminé: {} comptes créés", imported.size());

            return ResponseEntity.ok(Map.of(
                    "message", "Import terminé",
                    "total", requests.size(),
                    "imported", imported.size(),
                    "accounts", imported));

        } catch (Exception e) {
            log.error("tierNumberErreur import: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Erreur lors de l'import"));
        }
    }
}
