package com.example.releve_bancaire.controller.accounting;

import com.example.releve_bancaire.entity.accounting.AccountingEntry;
import com.example.releve_bancaire.entity.auth.Dossier;
import com.example.releve_bancaire.entity.dynamic.DynamicInvoice;
import com.example.releve_bancaire.entity.invoice.InvoiceStatus;
import com.example.releve_bancaire.repository.AccountingEntryDao;
import com.example.releve_bancaire.repository.DossierDao;
import com.example.releve_bancaire.repository.DynamicInvoiceDao;
import com.example.releve_bancaire.utils.InvoiceTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/accounting/journal")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AccountingJournalController {

    private final AccountingEntryDao accountingEntryDao;
    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final DossierDao dossierDao;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/entries")
    public ResponseEntity<?> listEntries(
            @RequestParam(value = "dossierId", required = false) Long dossierId) {
        Long resolvedDossierId = resolveDossierId(dossierId);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        List<AccountingEntry> entries = accountingEntryDao
                .findByDossierIdOrderByEntryDateDescCreatedAtDesc(resolvedDossierId);
        List<Map<String, Object>> response = toResponseWithInvoiceCounter(entries);

        return ResponseEntity.ok(Map.of(
                "count", response.size(),
                "entries", response));
    }

    @GetMapping("/defaults")
    public ResponseEntity<?> getJournalDefaults(
            @RequestParam(value = "dossierId", required = false) Long dossierId) {
        Long resolvedDossierId = resolveDossierId(dossierId);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }
        Dossier dossier = dossierDao.findById(resolvedDossierId).orElse(null);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "dossier_not_found"));
        }

        return ResponseEntity.ok(Map.of(
                "dossierId", dossier.getId(),
                "purchaseJournal", optionalString(dossier.getDefaultPurchaseJournal()),
                "salesJournal", optionalString(dossier.getDefaultSalesJournal())));
    }

    @PutMapping("/defaults")
    public ResponseEntity<?> updateJournalDefaults(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestBody Map<String, Object> body) {
        Long resolvedDossierId = resolveDossierId(dossierId);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }
        Dossier dossier = dossierDao.findById(resolvedDossierId).orElse(null);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "dossier_not_found"));
        }

        String purchaseJournal = trimToNull(body != null ? body.get("purchaseJournal") : null);
        String salesJournal = trimToNull(body != null ? body.get("salesJournal") : null);

        dossier.setDefaultPurchaseJournal(purchaseJournal);
        dossier.setDefaultSalesJournal(salesJournal);
        dossierDao.save(dossier);

        return ResponseEntity.ok(Map.of(
                "dossierId", dossier.getId(),
                "purchaseJournal", optionalString(dossier.getDefaultPurchaseJournal()),
                "salesJournal", optionalString(dossier.getDefaultSalesJournal())));
    }

    @PostMapping("/entries/from-invoice/{id}")
    @Transactional
    public ResponseEntity<?> accountInvoice(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId) {
        Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
        if (invoiceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DynamicInvoice invoice = invoiceOpt.get();
        Long resolvedDossierId = resolveDossierId(dossierId != null ? dossierId : invoice.getDossierId());
        Dossier dossier = resolvedDossierId != null ? dossierDao.findById(resolvedDossierId).orElse(null) : null;

        if (!isEligibleForAccounting(invoice.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invoice_not_validated",
                    "currentStatus", statusName(invoice.getStatus())));
        }

        if (Boolean.TRUE.equals(invoice.getAccounted())) {
            List<AccountingEntry> existing = accountingEntryDao.findByInvoiceIdOrderByIdAsc(invoice.getId());
            return ResponseEntity.ok(Map.of(
                    "message", "Facture deja comptabilisee",
                    "entries", toResponseWithInvoiceCounter(existing)));
        }

        AccountingInputs inputs = resolveInputs(invoice, dossier);
        if (!inputs.isValid()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_accounting_data",
                    "details", inputs.getErrors()));
        }

        List<AccountingEntry> entries = buildEntries(invoice, inputs);
        List<AccountingEntry> saved = accountingEntryDao.saveAll(entries);
        CptjornalSyncResult cptjornalSync = safeSyncCptjornalRows(saved, invoice, resolvedDossierId);

        invoice.setAccounted(true);
        invoice.setAccountedAt(java.time.LocalDateTime.now());
        invoice.setAccountedBy("system");
        dynamicInvoiceDao.save(invoice);

        return ResponseEntity.ok(Map.of(
                "message", "Facture comptabilisee",
                "hasMissingAccounts", !inputs.missingAccountWarnings.isEmpty(),
                "missingAccounts", inputs.missingAccountWarnings,
                "cptjornalSynced", cptjornalSync.success,
                "cptjornalInserted", cptjornalSync.insertedRows,
                "cptjornalMessage", cptjornalSync.message,
                "entries", toResponseWithInvoiceCounter(saved)));
    }

    @GetMapping("/entries/preview/from-invoice/{id}")
    public ResponseEntity<?> previewInvoiceEntries(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId) {
        Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
        if (invoiceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DynamicInvoice invoice = invoiceOpt.get();
        Long resolvedDossierId = resolveDossierId(dossierId != null ? dossierId : invoice.getDossierId());
        Dossier dossier = resolvedDossierId != null ? dossierDao.findById(resolvedDossierId).orElse(null) : null;

        if (!isEligibleForAccounting(invoice.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invoice_not_validated",
                    "currentStatus", statusName(invoice.getStatus())));
        }

        if (Boolean.TRUE.equals(invoice.getAccounted())) {
            List<AccountingEntry> existing = accountingEntryDao.findByInvoiceIdOrderByIdAsc(invoice.getId());
            return ResponseEntity.ok(Map.of(
                    "message", "Facture deja comptabilisee",
                    "alreadyAccounted", true,
                    "entries", toResponseWithInvoiceCounter(existing)));
        }

        AccountingInputs inputs = resolveInputs(invoice, dossier);
        if (!inputs.isValid()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_accounting_data",
                    "details", inputs.getErrors()));
        }

        List<AccountingEntry> preview = buildEntries(invoice, inputs);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (AccountingEntry entry : preview) {
            entries.add(toResponse(entry, 1));
        }

        return ResponseEntity.ok(Map.of(
                "message", "Apercu du journal facture",
                "alreadyAccounted", false,
                "hasMissingAccounts", !inputs.missingAccountWarnings.isEmpty(),
                "missingAccounts", inputs.missingAccountWarnings,
                "entries", entries));
    }

    @PostMapping("/entries/rebuild/{id}")
    @Transactional
    public ResponseEntity<?> rebuildEntries(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId) {
        Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
        if (invoiceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DynamicInvoice invoice = invoiceOpt.get();
        Long resolvedDossierId = resolveDossierId(dossierId != null ? dossierId : invoice.getDossierId());
        Dossier dossier = resolvedDossierId != null ? dossierDao.findById(resolvedDossierId).orElse(null) : null;

        if (!isEligibleForAccounting(invoice.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invoice_not_validated",
                    "currentStatus", statusName(invoice.getStatus())));
        }

        List<AccountingEntry> existing = accountingEntryDao.findByInvoiceIdOrderByIdAsc(invoice.getId());
        if (!existing.isEmpty()) {
            accountingEntryDao.deleteAll(existing);
        }

        AccountingInputs inputs = resolveInputs(invoice, dossier);
        if (!inputs.isValid()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_accounting_data",
                    "details", inputs.getErrors()));
        }

        List<AccountingEntry> entries = buildEntries(invoice, inputs);
        List<AccountingEntry> saved = accountingEntryDao.saveAll(entries);
        CptjornalSyncResult cptjornalSync = safeSyncCptjornalRows(saved, invoice, resolvedDossierId);

        invoice.setAccounted(true);
        invoice.setAccountedAt(java.time.LocalDateTime.now());
        invoice.setAccountedBy("system");
        dynamicInvoiceDao.save(invoice);

        return ResponseEntity.ok(Map.of(
                "message", "Ecritures reconstruites",
                "hasMissingAccounts", !inputs.missingAccountWarnings.isEmpty(),
                "missingAccounts", inputs.missingAccountWarnings,
                "cptjornalSynced", cptjornalSync.success,
                "cptjornalInserted", cptjornalSync.insertedRows,
                "cptjornalMessage", cptjornalSync.message,
                "entries", toResponseWithInvoiceCounter(saved)));
    }

    private record TableRef(String schema, String name) {
        String qualified() {
            return "`" + schema + "`.`" + name + "`";
        }
    }

    private record CptjornalSyncResult(boolean success, int insertedRows, String message) {
    }

    private CptjornalSyncResult safeSyncCptjornalRows(
            List<AccountingEntry> entries,
            DynamicInvoice invoice,
            Long dossierId) {
        try {
            return syncCptjornalRows(entries, invoice, dossierId);
        } catch (Exception ex) {
            log.warn("Sync cptjornal echouee (non bloquante): {}", ex.getMessage(), ex);
            return new CptjornalSyncResult(false, 0, "Sync cptjornal echouee: " + ex.getMessage());
        }
    }

    private CptjornalSyncResult syncCptjornalRows(
            List<AccountingEntry> entries,
            DynamicInvoice invoice,
            Long dossierId) {
        if (entries == null || entries.isEmpty()) {
            return new CptjornalSyncResult(true, 0, "Aucune ecriture a synchroniser");
        }

        TableRef table = resolveCptjornalTable();
        if (table == null) {
            return new CptjornalSyncResult(false, 0, "Table cptjornal introuvable");
        }

        Map<String, String> columns = resolveColumnMap(table);
        if (columns.isEmpty()) {
            return new CptjornalSyncResult(false, 0, "Colonnes cptjornal introuvables");
        }

        long nextNmouv = resolveNextNmouv(table, columns);
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;

        try {
            for (int i = 0; i < entries.size(); i++) {
                AccountingEntry entry = entries.get(i);
                long nmouv = nextNmouv + i;

                LocalDate entryDate = entry.getEntryDate();
                if (entryDate == null) {
                    entryDate = LocalDate.now();
                }

                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                putRowValue(row, columns, nmouv, "nmouv");
                putRowValue(row, columns, nmouv, "numero");
                putRowValue(row, columns, nmouv, "nmmouv");
                putRowValue(row, columns, entryDate.getDayOfMonth(), "dat");
                putRowValue(row, columns, entryDate, "datcompl");
                putRowValue(row, columns, entryDate, "dat_insert");
                putRowValue(row, columns, now, "dat_update");
                putRowValue(row, columns, entry.getJournal(), "ndosjrn", "jrn_org");
                putRowValue(row, columns, entry.getAccountNumber(), "ncompt", "compte_t", "compte_org");
                putRowValue(row, columns, safeMoney(entry.getDebit()), "debit");
                putRowValue(row, columns, safeMoney(entry.getCredit()), "credit");
                putRowValue(row, columns, optionalString(entry.getLabel()), "ecriture");
                putRowValue(row, columns, "1", "valider");
                putRowValue(row, columns, entryDate.getMonthValue(), "nmois");
                putRowValue(row, columns, String.format("%02d", entryDate.getMonthValue()), "mois", "mois_org");
                putRowValue(row, columns, dossierId, "cod_dos");
                putRowValue(row, columns, invoice.getId(), "facture");
                putRowValue(row, columns, firstNonBlank(invoice.getInvoiceNumber(), invoice.getFieldAsString("invoiceNumber")),
                        "piece", "piece_c", "numero_org");

                if (!row.isEmpty()) {
                    insertDynamicRow(table, row);
                    inserted++;
                }
            }
        } catch (DataAccessException ex) {
            log.warn("Sync cptjornal impossible: {}", ex.getMessage());
            return new CptjornalSyncResult(false, inserted, "Erreur insertion cptjornal: " + ex.getMessage());
        }

        return new CptjornalSyncResult(true, inserted, "Synchronisation cptjornal terminee");
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value;
    }

    private void insertDynamicRow(TableRef table, LinkedHashMap<String, Object> row) {
        List<String> cols = new ArrayList<>(row.keySet());
        String columnClause = String.join(", ", cols.stream().map(c -> "`" + c + "`").toList());
        String placeholders = String.join(", ", cols.stream().map(c -> "?").toList());
        String sql = "INSERT INTO " + table.qualified() + " (" + columnClause + ") VALUES (" + placeholders + ")";

        List<Object> args = new ArrayList<>(cols.size());
        for (String col : cols) {
            Object value = row.get(col);
            if (value instanceof LocalDate date) {
                args.add(Date.valueOf(date));
            } else if (value instanceof LocalDateTime dt) {
                args.add(Timestamp.valueOf(dt));
            } else {
                args.add(value);
            }
        }
        jdbcTemplate.update(sql, args.toArray());
    }

    private void putRowValue(LinkedHashMap<String, Object> row, Map<String, String> cols, Object value, String... aliases) {
        if (value == null || aliases == null) {
            return;
        }
        for (String alias : aliases) {
            if (alias == null) {
                continue;
            }
            String key = alias.toLowerCase();
            if (row.containsKey(cols.get(key))) {
                return;
            }
            String realColumn = cols.get(key);
            if (realColumn != null) {
                row.put(realColumn, value);
                return;
            }
        }
    }

    private long resolveNextNmouv(TableRef table, Map<String, String> columns) {
        try {
            String nmouvCol = columns.get("nmouv");
            if (nmouvCol != null) {
                Long max = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(`" + nmouvCol + "`), 0) FROM " + table.qualified(),
                        Long.class);
                return (max == null ? 0L : max) + 1L;
            }
            String numeroCol = columns.get("numero");
            if (numeroCol != null) {
                Long max = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(`" + numeroCol + "`), 0) FROM " + table.qualified(),
                        Long.class);
                return (max == null ? 0L : max) + 1L;
            }
        } catch (DataAccessException ex) {
            log.warn("Impossible de calculer le prochain nmouv: {}", ex.getMessage());
        }
        return 1L;
    }

    private TableRef resolveCptjornalTable() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT table_schema, table_name " +
                            "FROM information_schema.tables " +
                            "WHERE table_name IN ('cptjornal', 'Cptjornal', 'cptJornal') " +
                            "ORDER BY (table_schema = DATABASE()) DESC, " +
                            "(table_name = 'cptjornal') DESC, (table_name = 'Cptjornal') DESC " +
                            "LIMIT 1");
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            Object schema = row.get("table_schema");
            Object name = row.get("table_name");
            if (schema == null || name == null) {
                return null;
            }
            return new TableRef(String.valueOf(schema), String.valueOf(name));
        } catch (DataAccessException ex) {
            return null;
        }
    }

    private Map<String, String> resolveColumnMap(TableRef table) {
        Map<String, String> columns = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?",
                    table.schema(),
                    table.name());
            Set<String> unique = new HashSet<>();
            for (Map<String, Object> row : rows) {
                Object raw = row.get("column_name");
                if (raw == null) {
                    continue;
                }
                String real = String.valueOf(raw);
                String normalized = real.toLowerCase();
                if (unique.add(normalized)) {
                    columns.put(normalized, real);
                }
            }
        } catch (DataAccessException ex) {
            log.warn("Impossible de lire les colonnes de {}.{}: {}",
                    table.schema(), table.name(), ex.getMessage());
        }
        return columns;
    }

    private List<AccountingEntry> buildEntries(DynamicInvoice invoice, AccountingInputs inputs) {
        List<AccountingEntry> entries = new ArrayList<>();
        boolean isAvoir = inputs.isAvoir;

        if (inputs.isMultiTvaScenario()) {
            for (int i = 0; i < inputs.htLines.size(); i++) {
                BigDecimal htLine = inputs.htLines.get(i);
                if (htLine == null || htLine.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                entries.add(AccountingEntry.builder()
                        .dossier(invoice.getDossier())
                        .invoice(invoice)
                        .invoiceNumber(inputs.invoiceNumber)
                        .supplierName(inputs.supplier)
                        .journal(inputs.journal)
                        .accountNumber(inputs.chargeAccount)
                        .entryDate(inputs.entryDate)
                        .debit(isAvoir ? BigDecimal.ZERO : htLine)
                        .credit(isAvoir ? htLine : BigDecimal.ZERO)
                        .label("HT " + (i + 1))
                        .createdBy("system")
                        .build());
            }

            for (int i = 0; i < inputs.tvaLines.size(); i++) {
                BigDecimal tvaLine = inputs.tvaLines.get(i);
                if (tvaLine == null || tvaLine.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                entries.add(AccountingEntry.builder()
                        .dossier(invoice.getDossier())
                        .invoice(invoice)
                        .invoiceNumber(inputs.invoiceNumber)
                        .supplierName(inputs.supplier)
                        .journal(inputs.journal)
                        .accountNumber(inputs.tvaAccount)
                        .entryDate(inputs.entryDate)
                        .debit(isAvoir ? BigDecimal.ZERO : tvaLine)
                        .credit(isAvoir ? tvaLine : BigDecimal.ZERO)
                        .label("TVA " + (i + 1))
                        .createdBy("system")
                        .build());
            }
        } else {
            entries.add(AccountingEntry.builder()
                    .dossier(invoice.getDossier())
                    .invoice(invoice)
                    .invoiceNumber(inputs.invoiceNumber)
                    .supplierName(inputs.supplier)
                    .journal(inputs.journal)
                    .accountNumber(inputs.chargeAccount)
                    .entryDate(inputs.entryDate)
                    .debit(isAvoir ? BigDecimal.ZERO : inputs.amountHT)
                    .credit(isAvoir ? inputs.amountHT : BigDecimal.ZERO)
                    .label("HT")
                    .createdBy("system")
                    .build());

            if (inputs.tva != null && inputs.tva.compareTo(BigDecimal.ZERO) > 0) {
                entries.add(AccountingEntry.builder()
                        .dossier(invoice.getDossier())
                        .invoice(invoice)
                        .invoiceNumber(inputs.invoiceNumber)
                        .supplierName(inputs.supplier)
                        .journal(inputs.journal)
                        .accountNumber(inputs.tvaAccount)
                        .entryDate(inputs.entryDate)
                        .debit(isAvoir ? BigDecimal.ZERO : inputs.tva)
                        .credit(isAvoir ? inputs.tva : BigDecimal.ZERO)
                        .label("TVA")
                        .createdBy("system")
                        .build());
            }
        }

        entries.add(AccountingEntry.builder()
                .dossier(invoice.getDossier())
                .invoice(invoice)
                .invoiceNumber(inputs.invoiceNumber)
                .supplierName(inputs.supplier)
                .journal(inputs.journal)
                .accountNumber(inputs.supplierAccount)
                .entryDate(inputs.entryDate)
                .debit(isAvoir ? inputs.amountTTC : BigDecimal.ZERO)
                .credit(isAvoir ? BigDecimal.ZERO : inputs.amountTTC)
                .label("TTC")
                .createdBy("system")
                .build());

        return entries;
    }

    private AccountingInputs resolveInputs(DynamicInvoice invoice, Dossier dossier) {
        AccountingInputs inputs = new AccountingInputs();

        inputs.isAvoir = Boolean.TRUE.equals(invoice.getIsAvoir())
                || InvoiceTypeDetector.isAvoir(invoice.getFieldsData(), invoice.getRawOcrText());
        inputs.invoiceNumber = firstNonBlank(invoice.getInvoiceNumber(), invoice.getFieldAsString("invoiceNumber"));
        inputs.supplier = firstNonBlank(invoice.getSupplier(), invoice.getTierName(),
                invoice.getFieldAsString("supplier"));
        inputs.entryDate = resolveEntryDate(invoice.getInvoiceDate(),
                invoice.getCreatedAt() != null ? invoice.getCreatedAt().toLocalDate() : null);

        inputs.chargeAccount = firstNonBlank(invoice.getChargeAccount(), invoice.getFieldAsString("chargeAccount"));
        inputs.tvaAccount = firstNonBlank(invoice.getTvaAccount(), invoice.getFieldAsString("tvaAccount"));
        inputs.supplierAccount = resolveSupplierAccount(invoice);
        inputs.journal = resolveJournal(invoice, dossier);

        Double ht = invoice.getAmountHT();
        Double tva = invoice.getTva();
        Double ttc = invoice.getAmountTTC();

        BigDecimal htAmount = ht != null ? toMoney(ht) : null;
        BigDecimal tvaAmount = tva != null ? toMoney(tva) : null;
        BigDecimal ttcAmount = ttc != null ? toMoney(ttc) : null;

        if (ttcAmount == null && htAmount != null) {
            ttcAmount = htAmount.add(tvaAmount != null ? tvaAmount : BigDecimal.ZERO);
        }

        if (htAmount == null && ttcAmount != null) {
            htAmount = ttcAmount.subtract(tvaAmount != null ? tvaAmount : BigDecimal.ZERO);
        }

        inputs.amountHT = htAmount;
        inputs.tva = tvaAmount != null ? tvaAmount : BigDecimal.ZERO;
        inputs.amountTTC = ttcAmount;

        inputs.tvaLines = toMoneyList(parseAmountList(invoice.getFieldAsString("tvaValues")));
        inputs.htLines = toMoneyList(parseAmountList(invoice.getFieldAsString("htValues")));

        if (inputs.hasUsableMultiLines()) {
            BigDecimal summedTva = sumMoney(inputs.tvaLines);
            if (summedTva.compareTo(BigDecimal.ZERO) > 0) {
                inputs.tva = summedTva;
            }
        } else {
            inputs.htLines.clear();
            inputs.tvaLines.clear();
        }

        if (inputs.amountHT != null && inputs.amountHT.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal computedTtc = inputs.amountHT.add(inputs.tva != null ? inputs.tva : BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            if (inputs.amountTTC == null || inputs.amountTTC.compareTo(BigDecimal.ZERO) <= 0) {
                inputs.amountTTC = computedTtc;
                log.info("TTC manquant pour facture {} -> TTC calcule a {}", inputs.invoiceNumber, computedTtc);
            } else {
                BigDecimal diff = inputs.amountTTC.subtract(computedTtc).abs();
                if (diff.compareTo(new BigDecimal("0.01")) > 0) {
                    log.warn("Journal desequilibre detecte facture {}: TTC={} vs HT+TVA={} -> correction appliquee",
                            inputs.invoiceNumber, inputs.amountTTC, computedTtc);
                    inputs.amountTTC = computedTtc;
                }
            }
        }

        if (inputs.isAvoir) {
            inputs.amountHT = abs(inputs.amountHT);
            inputs.tva = abs(inputs.tva);
            inputs.amountTTC = abs(inputs.amountTTC);
            inputs.tvaLines = absMoneyList(inputs.tvaLines);
            inputs.htLines = absMoneyList(inputs.htLines);
        }

        inputs.applyMissingAccountFallbacks();
        inputs.validate();
        return inputs;
    }

    private Long resolveDossierId(Long dossierId) {
        if (dossierId != null) {
            return dossierId;
        }
        return dossierDao.findAll().stream()
                .findFirst()
                .map(Dossier::getId)
                .orElse(null);
    }

    private BigDecimal toMoney(Double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal abs(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.abs();
    }

    private List<BigDecimal> absMoneyList(List<BigDecimal> values) {
        List<BigDecimal> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (BigDecimal value : values) {
            if (value != null) {
                result.add(abs(value).setScale(2, RoundingMode.HALF_UP));
            }
        }
        return result;
    }

    private List<Double> parseAmountList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        String[] tokens = raw.split("\\|");
        for (String token : tokens) {
            if (token == null) {
                continue;
            }
            String cleaned = token.trim()
                    .replace("\u00A0", "")
                    .replace(" ", "")
                    .replace(",", ".")
                    .replaceAll("[^0-9.\\-]", "");
            if (cleaned.isBlank()) {
                continue;
            }
            try {
                double parsed = Double.parseDouble(cleaned);
                if (parsed > 0) {
                    values.add(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return values;
    }

    private List<BigDecimal> toMoneyList(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        List<BigDecimal> result = new ArrayList<>();
        for (Double value : values) {
            if (value != null) {
                result.add(toMoney(value));
            }
        }
        return result;
    }

    private BigDecimal sumMoney(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value != null) {
                sum = sum.add(value);
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private String resolveSupplierAccount(DynamicInvoice invoice) {
        if (invoice.getTier() != null) {
            String display = invoice.getTier().getDisplayAccount();
            if (display != null && !display.isBlank()) {
                return display;
            }
            String tierNumber = invoice.getTier().getTierNumber();
            if (tierNumber != null && !tierNumber.isBlank()) {
                return tierNumber;
            }
        }
        return firstNonBlank(
                invoice.getFieldAsString("tierNumber"),
                invoice.getFieldAsString("collectifAccount"));
    }

    private LocalDate resolveEntryDate(String rawDate, LocalDate fallback) {
        if (rawDate != null && !rawDate.isBlank()) {
            LocalDate parsed = parseDate(rawDate);
            if (parsed != null) {
                return parsed;
            }
        }
        return fallback != null ? fallback : LocalDate.now();
    }

    private LocalDate parseDate(String raw) {
        String value = raw.trim();
        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DateTimeFormatter.ofPattern("dd/MM/yy"),
                DateTimeFormatter.ofPattern("dd-MM-yy"));
        for (DateTimeFormatter formatter : formats) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null)
            return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isEligibleForAccounting(InvoiceStatus status) {
        return status == InvoiceStatus.VALIDATED
                || status == InvoiceStatus.READY_TO_VALIDATE
                || status == InvoiceStatus.TREATED;
    }

    private String statusName(InvoiceStatus status) {
        return status != null ? status.name() : "UNKNOWN";
    }

    private String resolveJournal(DynamicInvoice invoice, Dossier dossier) {
        String purchaseDefault = dossier != null ? trimToNull(dossier.getDefaultPurchaseJournal()) : null;
        String salesDefault = dossier != null ? trimToNull(dossier.getDefaultSalesJournal()) : null;

        if (purchaseDefault != null) {
            return purchaseDefault;
        }
        if (salesDefault != null) {
            return salesDefault;
        }
        return "ACHAT";
    }

    private String optionalString(String value) {
        return value == null ? "" : value;
    }

    private String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Map<String, Object> toResponse(AccountingEntry entry, Integer acIndex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("AC", acIndex);
        response.put("id", entry.getId());
        response.put("dossierId", entry.getDossierId());
        response.put("invoiceId", entry.getInvoiceId());
        response.put("invoiceNumber", entry.getInvoiceNumber());
        response.put("supplier", entry.getSupplierName());
        response.put("journal", entry.getJournal());
        response.put("accountNumber", entry.getAccountNumber());
        response.put("entryDate", entry.getEntryDate());
        response.put("debit", entry.getDebit());
        response.put("credit", entry.getCredit());
        response.put("label", entry.getLabel());
        response.put("createdAt", entry.getCreatedAt());
        response.put("createdBy", entry.getCreatedBy());
        return response;
    }

    private List<Map<String, Object>> toResponseWithInvoiceCounter(List<AccountingEntry> entries) {
        List<Map<String, Object>> response = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return response;
        }
        Map<Long, Integer> invoiceCounter = new LinkedHashMap<>();
        int current = 0;
        for (AccountingEntry entry : entries) {
            Integer acIndex = null;
            Long invoiceId = entry.getInvoiceId();
            if (invoiceId != null) {
                acIndex = invoiceCounter.get(invoiceId);
                if (acIndex == null) {
                    current += 1;
                    acIndex = current;
                    invoiceCounter.put(invoiceId, acIndex);
                }
            }
            response.add(toResponse(entry, acIndex));
        }
        return response;
    }

    private static class AccountingInputs {
        private static final String FALLBACK_CHARGE_ACCOUNT = "MISSING-CHARGE-HT";
        private static final String FALLBACK_SUPPLIER_ACCOUNT = "MISSING-FOURNISSEUR";
        private static final String FALLBACK_TVA_ACCOUNT = "MISSING-TVA";

        private String invoiceNumber;
        private String supplier;
        private LocalDate entryDate;
        private String chargeAccount;
        private String journal;
        private String tvaAccount;
        private String supplierAccount;
        private BigDecimal amountHT;
        private BigDecimal tva;
        private BigDecimal amountTTC;
        private List<BigDecimal> htLines = new ArrayList<>();
        private List<BigDecimal> tvaLines = new ArrayList<>();
        private boolean isAvoir;
        private final List<String> errors = new ArrayList<>();
        private final List<String> missingAccountWarnings = new ArrayList<>();

        private boolean isMultiTvaScenario() {
            return hasUsableMultiLines();
        }

        private boolean hasUsableMultiLines() {
            return htLines != null
                    && tvaLines != null
                    && htLines.size() >= 2
                    && tvaLines.size() >= 2
                    && htLines.size() == tvaLines.size();
        }

        private void validate() {
            if (amountHT == null || amountHT.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("Montant HT invalide");
            }
            if (amountTTC == null || amountTTC.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("Montant TTC invalide");
            }
        }

        private boolean isValid() {
            return errors.isEmpty();
        }

        private List<String> getErrors() {
            return errors;
        }

        private void applyMissingAccountFallbacks() {
            if (chargeAccount == null || chargeAccount.isBlank()) {
                chargeAccount = FALLBACK_CHARGE_ACCOUNT;
                missingAccountWarnings.add("Compte charge/HT manquant");
            }
            if (supplierAccount == null || supplierAccount.isBlank()) {
                supplierAccount = FALLBACK_SUPPLIER_ACCOUNT;
                missingAccountWarnings.add("Compte fournisseur manquant");
            }
            if (tva != null && tva.compareTo(BigDecimal.ZERO) > 0
                    && (tvaAccount == null || tvaAccount.isBlank())) {
                tvaAccount = FALLBACK_TVA_ACCOUNT;
                missingAccountWarnings.add("Compte TVA manquant");
            }
        }
    }
}



