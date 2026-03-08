package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.accounting_repository.ComptjrnJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BankJournalResolverService {

    private final ComptjrnJdbcRepository comptjrnJdbcRepository;

    public Optional<String> findCodejrnByCompte(String compte) {
        if (compte == null || compte.isBlank()) {
            return Optional.empty();
        }
        return comptjrnJdbcRepository.findCodejrnByCdcmptctr(compte.trim());
    }

    public Map<String, String> findCodejrnsByComptes(Collection<String> comptes) {
        Map<String, String> journals = new LinkedHashMap<>();
        if (comptes == null || comptes.isEmpty()) {
            return journals;
        }

        for (String compte : comptes) {
            if (compte == null || compte.isBlank()) {
                continue;
            }
            String normalized = compte.trim();
            journals.computeIfAbsent(normalized,
                    key -> findCodejrnByCompte(key).orElse(""));
        }
        return journals;
    }
}
