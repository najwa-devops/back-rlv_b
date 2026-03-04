package com.example.releve_bancaire.accounting_services;

import com.example.releve_bancaire.accounting_entity.AccountingEntry;
import com.example.releve_bancaire.accounting_repository.AccountingEntryRepository;
import com.example.releve_bancaire.accounting_repository.CptjournalJdbcRepository;
import com.example.releve_bancaire.accounting_repository.CptjournalSyncTrackerRepository;
import com.example.releve_bancaire.banking_entity.BankStatement;
import com.example.releve_bancaire.banking_entity.BankStatus;
import com.example.releve_bancaire.banking_entity.BankTransaction;
import com.example.releve_bancaire.banking_repository.BankStatementRepository;
import com.example.releve_bancaire.banking_repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ComptabilisationWorkflowService {

    private static final String DEFAULT_TX_COMPTE = "349700000";
    private static final String ACCOUNT_CODE_REGEX = "^\\d{9}$";
    private static final String VALIDER_TRUE = "1";

    private final BankStatementRepository bankStatementRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final AccountingEntryRepository accountingEntryRepository;
    private final CptjournalJdbcRepository cptjournalJdbcRepository;
    private final CptjournalSyncTrackerRepository cptjournalSyncTrackerRepository;

    @Value("${accounting.default-journal:BQ}")
    private String defaultJournal;

    @Value("${accounting.principal-account:514100000}")
    private String principalAccount;

    private final Map<String, SimulationContext> simulations = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public SimulationResult simulate(Long statementId) {
        if (statementId == null) {
            throw new IllegalArgumentException("statementId est obligatoire.");
        }

        BankStatement statement = bankStatementRepository.findById(statementId)
                .orElseThrow(() -> new IllegalArgumentException("Releve introuvable."));

        List<BankTransaction> transactions = bankTransactionRepository
                .findByStatementIdOrderByTransactionIndexAsc(statementId);
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction a comptabiliser pour ce releve.");
        }

        String journal = sanitizeJournal(defaultJournal);
        String bankAccount = sanitizeAccount(principalAccount, "compte principal");

        int nmois = resolveMonth(statement, transactions);
        String moisTexte = monthLabel(nmois);
        String nmoisTexte = String.format("%02d", nmois);
        long fallbackNumero = accountingEntryRepository.findMaxNumeroByJournalAndMonth(journal, nmois) + 1;

        List<SimulatedEntry> rows = new ArrayList<>();
        for (BankTransaction tx : transactions) {
            LocalDate dateOperation = tx.getDateOperation();
            if (dateOperation == null) {
                continue;
            }

            BigDecimal debit = tx.getDebit() == null ? BigDecimal.ZERO : tx.getDebit();
            BigDecimal credit = tx.getCredit() == null ? BigDecimal.ZERO : tx.getCredit();
            String libelle = tx.getLibelle() == null ? "" : tx.getLibelle();
            String contrepartie = sanitizeTransactionAccount(tx.getCompte());

            long numeroMain = resolveTransactionNumero(tx, fallbackNumero);
            if (tx.getTransactionIndex() == null || tx.getTransactionIndex() <= 0) {
                fallbackNumero++;
            }
            // 1ere ecriture: compte de la transaction choisi dans le detail du releve.
            rows.add(new SimulatedEntry(
                    numeroMain,
                    moisTexte,
                    nmoisTexte,
                    dateOperation,
                    journal,
                    contrepartie,
                    libelle,
                    debit,
                    credit,
                    tx.getId(),
                    false));

            // 2eme ecriture (contrepartie): compte principal provenant du XML/config directeur.
            rows.add(new SimulatedEntry(
                    numeroMain,
                    moisTexte,
                    nmoisTexte,
                    dateOperation,
                    journal,
                    bankAccount,
                    libelle,
                    credit,
                    debit,
                    tx.getId(),
                    true));
        }

        String simulationId = UUID.randomUUID().toString();
        simulations.put(simulationId, new SimulationContext(
                simulationId,
                statementId,
                journal,
                nmois,
                rows,
                LocalDateTime.now()));

        return new SimulationResult(simulationId, statementId, journal, nmois, rows);
    }

    @Transactional
    public ConfirmationResult confirm(String simulationId, String userId) {
        if (simulationId == null || simulationId.isBlank()) {
            throw new IllegalArgumentException("simulationId est obligatoire.");
        }

        SimulationContext context = simulations.get(simulationId);
        if (context == null) {
            throw new IllegalArgumentException("Simulation introuvable ou expiree. Relancez la simulation.");
        }

        List<AccountingEntry> entries = new ArrayList<>(context.rows().size());
        List<CptjournalJdbcRepository.CptjournalRow> cptjournalRows = new ArrayList<>(context.rows().size());
        long cptjournalBaseNumero = cptjournalJdbcRepository.findMaxNumero() + 1;
        for (SimulatedEntry row : context.rows()) {
            AccountingEntry entry = new AccountingEntry();
            entry.setNumero(row.numero());
            entry.setMois(row.moisTexte());
            entry.setNmois(context.nmois());
            entry.setDateComplete(row.dateOperation());
            entry.setDate(row.dateOperation().getDayOfMonth());
            entry.setEcriture(row.libelle());
            entry.setDebit(row.debit());
            entry.setCredit(row.credit());
            entry.setNdosjrn(context.journal());
            entry.setNcompte(row.ncompte());
            entry.setSourceStatementId(context.statementId());
            entry.setSourceTransactionId(row.sourceTransactionId());
            entry.setIsCounterpart(row.counterpart());
            entry.setBatchId(simulationId);
            entries.add(entry);

            long numeroCptjournal = cptjournalBaseNumero + (row.numero() - 1L);
            LocalDate dateOperation = row.dateOperation();
            cptjournalRows.add(new CptjournalJdbcRepository.CptjournalRow(
                    numeroCptjournal,
                    context.journal(),
                    context.nmois(),
                    row.moisTexte(),
                    row.ncompte(),
                    row.libelle(),
                    row.debit(),
                    row.credit(),
                    VALIDER_TRUE,
                    dateOperation,
                    dateOperation.getDayOfMonth(),
                    dateOperation.getYear(),
                    resolveMntRester(row.ncompte(), row.debit(), row.credit())));
        }
        accountingEntryRepository.saveAll(entries);
        if (!cptjournalSyncTrackerRepository.isSynced(context.statementId())) {
            cptjournalJdbcRepository.insertAll(cptjournalRows);
            cptjournalSyncTrackerRepository.markSynced(context.statementId());
        }

        BankStatement statement = bankStatementRepository.findById(context.statementId())
                .orElseThrow(() -> new IllegalArgumentException("Releve introuvable."));
        String actor = (userId == null || userId.isBlank()) ? "system" : userId;
        if (statement.getStatus() == BankStatus.VALIDATED || statement.getStatus() == BankStatus.COMPTABILISE) {
            statement.markAsAccounted(actor);
        } else {
            statement.setStatus(BankStatus.COMPTABILISE);
            statement.setAccountedAt(LocalDateTime.now());
            statement.setAccountedBy(actor);
        }
        bankStatementRepository.save(statement);

        simulations.remove(simulationId);
        return new ConfirmationResult(simulationId, context.statementId(), entries.size(), statement.getStatus().name());
    }

    @Transactional
    public int syncCptjournalFromExistingAccountingEntries(Long statementId) {
        if (statementId == null) {
            throw new IllegalArgumentException("statementId est obligatoire.");
        }
        if (cptjournalSyncTrackerRepository.isSynced(statementId)) {
            return 0;
        }

        List<AccountingEntry> entries = accountingEntryRepository.findBySourceStatementIdOrderByNumeroAscIdAsc(statementId);
        if (entries.isEmpty()) {
            return 0;
        }

        long minNumero = entries.stream()
                .map(AccountingEntry::getNumero)
                .filter(n -> n != null)
                .min(Long::compareTo)
                .orElse(1L);
        long baseNumero = cptjournalJdbcRepository.findMaxNumero() + 1;

        List<CptjournalJdbcRepository.CptjournalRow> rows = new ArrayList<>(entries.size());
        for (AccountingEntry entry : entries) {
            LocalDate date = entry.getDateComplete();
            if (date == null) {
                continue;
            }
            long numero = baseNumero + (entry.getNumero() - minNumero);
            BigDecimal debit = entry.getDebit() == null ? BigDecimal.ZERO : entry.getDebit();
            BigDecimal credit = entry.getCredit() == null ? BigDecimal.ZERO : entry.getCredit();
            rows.add(new CptjournalJdbcRepository.CptjournalRow(
                    numero,
                    entry.getNdosjrn(),
                    entry.getNmois(),
                    entry.getMois(),
                    entry.getNcompte(),
                    entry.getEcriture(),
                    debit,
                    credit,
                    VALIDER_TRUE,
                    date,
                    date.getDayOfMonth(),
                    date.getYear(),
                    resolveMntRester(entry.getNcompte(), debit, credit)));
        }

        cptjournalJdbcRepository.insertAll(rows);
        cptjournalSyncTrackerRepository.markSynced(statementId);
        return rows.size();
    }

    private int resolveMonth(BankStatement statement, List<BankTransaction> transactions) {
        if (statement.getMonth() != null && statement.getMonth() >= 1 && statement.getMonth() <= 12) {
            return statement.getMonth();
        }
        for (BankTransaction tx : transactions) {
            if (tx.getDateOperation() != null) {
                return tx.getDateOperation().getMonthValue();
            }
        }
        return LocalDate.now().getMonthValue();
    }

    private String monthLabel(int nmois) {
        return Month.of(nmois).getDisplayName(TextStyle.FULL, Locale.FRENCH);
    }

    private String sanitizeJournal(String journal) {
        if (journal == null || journal.isBlank()) {
            throw new IllegalArgumentException("Journal de comptabilisation manquant.");
        }
        return journal.trim();
    }

    private String sanitizeAccount(String account, String label) {
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException(label + " manquant.");
        }
        String value = account.trim();
        if (!value.matches(ACCOUNT_CODE_REGEX)) {
            throw new IllegalArgumentException(label + " invalide (9 chiffres attendus).");
        }
        return value;
    }

    private String sanitizeTransactionAccount(String account) {
        if (account == null || account.isBlank()) {
            return DEFAULT_TX_COMPTE;
        }
        String value = account.trim();
        return value.matches(ACCOUNT_CODE_REGEX) ? value : DEFAULT_TX_COMPTE;
    }

    private long resolveTransactionNumero(BankTransaction tx, long fallbackNumero) {
        if (tx.getTransactionIndex() != null && tx.getTransactionIndex() > 0) {
            return tx.getTransactionIndex().longValue();
        }
        return fallbackNumero;
    }

    private BigDecimal resolveMntRester(String ncompte, BigDecimal debit, BigDecimal credit) {
        if (ncompte == null) {
            return null;
        }
        String value = ncompte.trim();
        if (value.startsWith("4411")
                || value.startsWith("342")
                || value.startsWith("1481")
                || value.startsWith("1486")) {
            return debit.subtract(credit).abs();
        }
        return null;
    }

    private record SimulationContext(
            String simulationId,
            Long statementId,
            String journal,
            int nmois,
            List<SimulatedEntry> rows,
            LocalDateTime createdAt) {
    }

    public record SimulatedEntry(
            long numero,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String ncompte,
            String libelle,
            BigDecimal debit,
            BigDecimal credit,
            Long sourceTransactionId,
            boolean counterpart) {
    }

    public record SimulationResult(
            String simulationId,
            Long statementId,
            String journal,
            int nmois,
            List<SimulatedEntry> entries) {
    }

    public record ConfirmationResult(
            String simulationId,
            Long statementId,
            int insertedEntries,
            String statementStatus) {
    }
}
