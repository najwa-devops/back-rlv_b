package com.example.releve_bancaire.banking_controller;

import com.example.releve_bancaire.account_tier.Account;
import com.example.releve_bancaire.repository.AccountDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v2/accounting/accounts", "/api/accounting/v2/accounts"})
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class AccountingAccountController {

    private final AccountDao accountDao;

    /**
     * V2 endpoint - for internal use only
     * Returns accounts from local database (not external catalog)
     */
    @GetMapping
    public ResponseEntity<?> list() {
        log.info("Fetching all local accounts");
        List<Account> accounts = accountDao.findAllByOrderByCodeAsc();
        log.info("Returning {} local accounts", accounts.size());
        return ResponseEntity.ok(Map.of("count", accounts.size(), "accounts", accounts));
    }

    @GetMapping("/options")
    public ResponseEntity<?> options() {
        List<AccountOption> options = accountDao.findAllByOrderByCodeAsc()
                .stream()
                .map(account -> new AccountOption(account.getCode(), account.getLibelle()))
                .toList();

        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    private record AccountOption(String code, String libelle) {
    }
}
