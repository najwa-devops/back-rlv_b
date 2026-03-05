package com.example.releve_bancaire.controller.account_tier;

import com.example.releve_bancaire.dto.account_tier.AccountDto;
import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.dto.account_tier.CreateAccountRequest;
import com.example.releve_bancaire.dto.account_tier.UpdateAccountRequest;
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
    @GetMapping("/{id}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> getAccountById(@PathVariable Long id) {
        log.debug("tierNumberGET /api/accounting/accounts/{}", id);

        Optional<AccountDto> accountOpt = accountService.getAccountById(id);

        if (accountOpt.isPresent()) {
            return ResponseEntity.ok(Map.of("account", accountOpt.get()));
        } else {
            Map<String, Object> errorResponse = Map.of(
                    "error", "Compte non trouvé avec ID: " + id);
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
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        log.debug("tierNumberGET /api/accounting/accounts (activeOnly={})", activeOnly);

        List<AccountDto> accounts = activeOnly
                ? accountService.getAllActiveAccounts()
                : accountService.getAllAccounts();

        return ResponseEntity.ok(Map.of(
                "count", accounts.size(),
                "accounts", accounts));
    }

    // ===================== RECHERCHE =====================
    @GetMapping("/search")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> searchAccounts(@RequestParam String query) {
        log.debug("tierNumberGET /api/accounting/accounts/search?query={}", query);

        List<AccountDto> accounts = accountService.searchAccounts(query);

        return ResponseEntity.ok(Map.of(
                "query", query,
                "count", accounts.size(),
                "accounts", accounts));
    }

    @GetMapping("/by-classe/{classe}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getAccountsByClasse(@PathVariable Integer classe) {
        log.debug("tierNumberGET /api/accounting/accounts/by-classe/{}", classe);

        try {
            List<AccountDto> accounts = accountService.getAccountsByClasse(classe);

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

        List<AccountDto> accounts = accountService.getFournisseurAccounts();

        return ResponseEntity.ok(Map.of(
                "type", "fournisseurs",
                "count", accounts.size(),
                "accounts", accounts));
    }

    @GetMapping("/charges")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getChargeAccounts() {
        log.debug("tierNumberGET /api/accounting/accounts/charges");

        List<AccountDto> accounts = accountService.getChargeAccounts();

        return ResponseEntity.ok(Map.of(
                "type", "charges",
                "count", accounts.size(),
                "accounts", accounts));
    }

    @GetMapping("/tva")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<Map<String, Object>> getTvaAccounts() {
        log.debug("tierNumberGET /api/accounting/accounts/tva");

        List<AccountDto> accounts = accountService.getTvaAccounts();

        return ResponseEntity.ok(Map.of(
                "type", "tva",
                "count", accounts.size(),
                "accounts", accounts));
    }

    // ===================== MISE À JOUR =====================
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateAccount(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAccountRequest request) {
        log.info("tierNumberPUT /api/accounting/accounts/{}", id);

        try {
            AccountDto account = accountService.updateAccount(id, request);

            log.info("tierNumberCompte mis à jour: ID={}, Code={}", account.getId(), account.getCode());

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
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deactivateAccount(@PathVariable Long id) {
        log.info("tierNumberDELETE /api/accounting/accounts/{}", id);

        try {
            accountService.deactivateAccount(id);

            log.info("tierNumberCompte désactivé: ID={}", id);

            return ResponseEntity.ok(Map.of(
                    "message", "Compte désactivé avec succès",
                    "accountId", id));

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

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activateAccount(@PathVariable Long id) {
        log.info("tierNumberPATCH /api/accounting/accounts/{}/activate", id);

        try {
            accountService.activateAccount(id);

            log.info("tierNumberCompte réactivé: ID={}", id);

            return ResponseEntity.ok(Map.of(
                    "message", "Compte réactivé avec succès",
                    "accountId", id));

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
