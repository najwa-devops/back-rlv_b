package com.example.releve_bancaire.banking_controller;

import com.example.releve_bancaire.account_tier.Compte;
import com.example.releve_bancaire.repository.CompteDao;
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

    private final CompteDao compteDao;

    /**
     * V2 endpoint - for internal use only
     * Returns accounts from local database (not external catalog)
     */
    @GetMapping
    public ResponseEntity<?> list() {
        log.info("Fetching all local comptes");
        List<Compte> comptes = compteDao.findAllByOrderByNumeroAsc();
        log.info("Returning {} local comptes", comptes.size());
        return ResponseEntity.ok(Map.of("count", comptes.size(), "comptes", comptes));
    }

    @GetMapping("/options")
    public ResponseEntity<?> options() {
        List<AccountOption> options = compteDao.findAllByOrderByNumeroAsc()
                .stream()
                .map(compte -> new AccountOption(compte.getNumero(), compte.getLibelle()))
                .toList();

        return ResponseEntity.ok(Map.of("count", options.size(), "comptes", options));
    }

    private record AccountOption(String code, String libelle) {
    }
}
