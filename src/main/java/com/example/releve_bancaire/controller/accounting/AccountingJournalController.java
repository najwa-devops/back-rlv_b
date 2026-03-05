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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/accounting/journal")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AccountingJournalController {

    private final AccountingEntryDao accountingEntryDao;
    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final DossierDao dossierDao;

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

        if (invoice.getStatus() != InvoiceStatus.VALIDATED) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invoice_not_validated",
                    "currentStatus", invoice.getStatus().name()));
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

        invoice.setAccounted(true);
        invoice.setAccountedAt(java.time.LocalDateTime.now());
        invoice.setAccountedBy("system");
        dynamicInvoiceDao.save(invoice);

        return ResponseEntity.ok(Map.of(
                "message", "Facture comptabilisee",
                "entries", toResponseWithInvoiceCounter(saved)));
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

        if (invoice.getStatus() != InvoiceStatus.VALIDATED) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invoice_not_validated",
                    "currentStatus", invoice.getStatus().name()));
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

        invoice.setAccounted(true);
        invoice.setAccountedAt(java.time.LocalDateTime.now());
        invoice.setAccountedBy("system");
        dynamicInvoiceDao.save(invoice);

        return ResponseEntity.ok(Map.of(
                "message", "Ecritures reconstruites",
                "entries", toResponseWithInvoiceCounter(saved)));
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
            if (chargeAccount == null || chargeAccount.isBlank()) {
                errors.add("Compte charge/HT manquant");
            }
            if (supplierAccount == null || supplierAccount.isBlank()) {
                errors.add("Compte fournisseur manquant");
            }
            if (amountHT == null || amountHT.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("Montant HT invalide");
            }
            if (amountTTC == null || amountTTC.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("Montant TTC invalide");
            }
            if (tva != null && tva.compareTo(BigDecimal.ZERO) > 0
                    && (tvaAccount == null || tvaAccount.isBlank())) {
                errors.add("Compte TVA manquant");
            }
        }

        private boolean isValid() {
            return errors.isEmpty();
        }

        private List<String> getErrors() {
            return errors;
        }
    }
}
