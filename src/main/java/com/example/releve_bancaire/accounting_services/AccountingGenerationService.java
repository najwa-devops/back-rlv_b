package com.example.releve_bancaire.accounting_services;

import com.example.releve_bancaire.accounting_entity.AccountingEntry;
import com.example.releve_bancaire.accounting_repository.AccountingEntryRepository;
import com.example.releve_bancaire.banking_entity.BankTransaction;
import com.example.releve_bancaire.banking_repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountingGenerationService {

    private static final String ACCOUNT_CODE_REGEX = "^\\d{9}$";

    private final WinsOsXmlParser winsOsXmlParser;
    private final BankTransactionRepository bankTransactionRepository;
    private final AccountingEntryRepository accountingEntryRepository;

    @Value("${accounting.default-counterparty-account:514000000}")
    private String defaultCounterpartyAccount;

    @Transactional
    public GenerationResult generateFromXml(byte[] xmlBytes, int nmois, Integer year) {
        if (nmois < 1 || nmois > 12) {
            throw new IllegalArgumentException("nmois invalide, attendu entre 1 et 12.");
        }

        WinsOsXmlParser.ParsedWinsOsXml payload = winsOsXmlParser.parse(new java.io.ByteArrayInputStream(xmlBytes));
        String comptePrincipal = sanitizeAccount(payload.comptePrincipal(), "compte principal XML");
        String journal = sanitizeJournal(payload.journal());
        String compteContrepartie = payload.compteContrepartie() == null || payload.compteContrepartie().isBlank()
                ? sanitizeAccount(defaultCounterpartyAccount, "compte contrepartie par defaut")
                : sanitizeAccount(payload.compteContrepartie(), "compte contrepartie XML");

        List<BankTransaction> transactions = bankTransactionRepository.findAccountingCandidates(nmois, year);
        if (transactions.isEmpty()) {
            return new GenerationResult(
                    0,
                    nmois,
                    journal,
                    0L,
                    payload.xmlDbUser() != null || payload.xmlDbPassword() != null,
                    "Aucune transaction VALIDATED/COMPTABILISE trouvee pour le mois demande.");
        }

        long currentMax = accountingEntryRepository.findMaxNumeroByJournalAndMonth(journal, nmois);
        long nextNumero = currentMax + 1;
        String monthLabel = monthLabel(nmois);

        List<AccountingEntry> toInsert = new ArrayList<>(transactions.size() * 2);
        for (BankTransaction tx : transactions) {
            if (tx.getDateOperation() == null) {
                continue;
            }

            BigDecimal debit = tx.getDebit() == null ? BigDecimal.ZERO : tx.getDebit();
            BigDecimal credit = tx.getCredit() == null ? BigDecimal.ZERO : tx.getCredit();
            String libelle = tx.getLibelle() == null ? "" : tx.getLibelle().trim();

            AccountingEntry main = new AccountingEntry();
            main.setNumero(nextNumero++);
            main.setMois(monthLabel);
            main.setNmois(nmois);
            main.setDateComplete(tx.getDateOperation());
            main.setDate(tx.getDateOperation().getDayOfMonth());
            main.setEcriture(libelle);
            main.setDebit(debit);
            main.setCredit(credit);
            main.setNcompte(comptePrincipal);
            main.setNdosjrn(journal);
            main.setSourceStatementId(tx.getStatement() != null ? tx.getStatement().getId() : null);
            main.setSourceTransactionId(tx.getId());
            main.setIsCounterpart(false);
            toInsert.add(main);

            AccountingEntry counterpart = new AccountingEntry();
            counterpart.setNumero(nextNumero++);
            counterpart.setMois(monthLabel);
            counterpart.setNmois(nmois);
            counterpart.setDateComplete(tx.getDateOperation());
            counterpart.setDate(tx.getDateOperation().getDayOfMonth());
            counterpart.setEcriture(libelle);
            counterpart.setDebit(credit);
            counterpart.setCredit(debit);
            counterpart.setNcompte(compteContrepartie);
            counterpart.setNdosjrn(journal);
            counterpart.setSourceStatementId(tx.getStatement() != null ? tx.getStatement().getId() : null);
            counterpart.setSourceTransactionId(tx.getId());
            counterpart.setIsCounterpart(true);
            toInsert.add(counterpart);
        }

        accountingEntryRepository.saveAll(toInsert);
        log.info("Comptabilisation auto terminee: {} ecritures generees (journal={}, nmois={})",
                toInsert.size(), journal, nmois);

        boolean xmlContainsCredentials = payload.xmlDbUser() != null || payload.xmlDbPassword() != null;
        return new GenerationResult(
                toInsert.size(),
                nmois,
                journal,
                nextNumero - 1,
                xmlContainsCredentials,
                xmlContainsCredentials
                        ? "Credentials detectes dans XML mais ignores. Utilisation de la configuration env."
                        : null);
    }

    @Transactional
    public GenerationResult generateFromXmlUrl(String xmlUrl, int nmois, Integer year) {
        if (xmlUrl == null || xmlUrl.isBlank()) {
            throw new IllegalArgumentException("Le lien XML est obligatoire.");
        }
        try (var input = URI.create(xmlUrl.trim()).toURL().openStream()) {
            return generateFromXml(input.readAllBytes(), nmois, year);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Impossible de lire le XML depuis le lien fourni: " + e.getMessage(), e);
        }
    }

    private String sanitizeAccount(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(fieldName + " manquant.");
        }
        String value = raw.trim();
        if (!value.matches(ACCOUNT_CODE_REGEX)) {
            throw new IllegalArgumentException(fieldName + " invalide: attendu 9 chiffres.");
        }
        return value;
    }

    private String sanitizeJournal(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("journal XML manquant.");
        }
        return raw.trim();
    }

    private String monthLabel(int nmois) {
        return Month.of(nmois).getDisplayName(TextStyle.FULL, Locale.FRENCH);
    }

    public record GenerationResult(
            int generatedEntries,
            int nmois,
            String journal,
            long lastNumero,
            boolean xmlContainsCredentials,
            String warning) {
    }
}
