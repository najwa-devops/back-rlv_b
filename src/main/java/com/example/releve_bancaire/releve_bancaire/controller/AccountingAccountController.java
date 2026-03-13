package com.example.releve_bancaire.releve_bancaire.controller;

import com.example.releve_bancaire.account_tier.Account;
import com.example.releve_bancaire.repository.AccountDao;
import com.example.releve_bancaire.releve_bancaire.services.ExternalComptesCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v2/accounting/accounts", "/api/accounting/accounts"})
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AccountingAccountController {

    private final AccountDao accountDao;
    private final ExternalComptesCatalogService externalComptesCatalogService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        // Source active: table distante "Comptes" (numero, libelle) sur le serveur externe.
        // Le paramètre activeOnly est conservé pour compatibilité API.
        List<Account> accounts = externalComptesCatalogService.loadAccounts();

        // Source locale historique (gardée commentée, ne pas supprimer):
        // List<Account> accounts = activeOnly
        //         ? accountDao.findByActiveTrueOrderByCodeAsc()
        //         : accountDao.findAllByOrderByCodeAsc();
        return ResponseEntity.ok(Map.of("count", accounts.size(), "accounts", accounts));
    }

    @GetMapping("/options")
    public ResponseEntity<?> options() {
        List<AccountOption> options = externalComptesCatalogService.loadAccounts()
                .stream()
                .map(account -> new AccountOption(account.getCode(), account.getLibelle()))
                .toList();

        // Source locale historique (gardée commentée, ne pas supprimer):
        // List<AccountOption> options = accountDao.findByActiveTrueOrderByCodeAsc()
        //         .stream()
        //         .map(account -> new AccountOption(account.getCode(), account.getLibelle()))
        //         .toList();

        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    private record AccountOption(String code, String libelle) {}
}
