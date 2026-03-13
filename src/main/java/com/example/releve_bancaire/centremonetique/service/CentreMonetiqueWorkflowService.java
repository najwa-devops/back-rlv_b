package com.example.releve_bancaire.centremonetique.service;

import com.example.releve_bancaire.banking_entity.BankTransaction;
import com.example.releve_bancaire.banking_repository.BankStatementRepository;
import com.example.releve_bancaire.banking_repository.BankTransactionRepository;
import com.example.releve_bancaire.centremonetique.dto.CentreMonetiqueBatchDetailDTO;
import com.example.releve_bancaire.centremonetique.dto.CentreMonetiqueBatchSummaryDTO;
import com.example.releve_bancaire.centremonetique.dto.CentreMonetiqueExtractionRow;
import com.example.releve_bancaire.centremonetique.dto.RapprochementResultDTO;
import com.example.releve_bancaire.centremonetique.entity.CentreMonetiqueBatch;
import com.example.releve_bancaire.centremonetique.entity.CentreMonetiqueTransaction;
import com.example.releve_bancaire.centremonetique.repository.CentreMonetiqueBatchRepository;
import com.example.releve_bancaire.centremonetique.repository.CentreMonetiqueTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CentreMonetiqueWorkflowService {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Extrait le numéro TPE depuis un libellé bancaire "VENTE PAR CARTE  000285". */
    private static final Pattern BANK_TPE_PATTERN = Pattern.compile(
            "VENTE\\s+PAR\\s+CARTE\\s+([A-Z0-9]{4,10})\\b",
            Pattern.CASE_INSENSITIVE);

    /** Extrait le code commerçant depuis un libellé bancaire "... ACQ86097 ...". */
    private static final Pattern BANK_ACQ_PATTERN = Pattern.compile(
            "ACQ([0-9]{4,10})\\b",
            Pattern.CASE_INSENSITIVE);

    /** Extrait le code commerçant depuis le texte OCR BARID_BANK "COMMERCANT : 86097". */
    private static final Pattern BARID_COMMERCANT_CODE_PATTERN = Pattern.compile(
            "(?i)COMMERCANT\\s*[:\\-]?\\s*(?:[^\\d\\n]{0,40})?([0-9]{4,10})");

    private final CentreMonetiqueBatchRepository batchRepository;
    private final CentreMonetiqueTransactionRepository transactionRepository;
    private final CentreMonetiqueExtractionService extractionService;
    private final BankTransactionRepository bankTransactionRepository;
    private final BankStatementRepository bankStatementRepository;

    @Transactional
    public CentreMonetiqueBatchDetailDTO uploadAndExtract(MultipartFile file,
                                                          Integer year,
                                                          CentreMonetiqueStructureType structureType,
                                                          String rib) throws Exception {
        CentreMonetiqueBatch batch = new CentreMonetiqueBatch();
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "document";
        String safeOriginalName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");

        batch.setFilename(UUID.randomUUID() + "_" + safeOriginalName);
        batch.setOriginalName(originalName);
        batch.setFileContentType(file.getContentType());
        batch.setFileSize(file.getSize());
        batch.setFileData(file.getBytes());
        batch.setStatus("PROCESSING");
        batch.setStructure((structureType != null ? structureType : CentreMonetiqueStructureType.AUTO).name());
        if (rib != null && !rib.isBlank()) {
            batch.setRib(rib.trim());
        }
        batch = batchRepository.save(batch);

        try {
            CentreMonetiqueExtractionService.ExtractionPayload payload = extractionService.extract(
                    batch.getFileData(),
                    batch.getOriginalName(),
                    year,
                    structureType);
            List<CentreMonetiqueExtractionRow> rows = payload.rows();
            batch.setRawOcrText(payload.rawOcrText());
            batch.setStructure(payload.detectedStructure() != null && !payload.detectedStructure().isBlank()
                    ? payload.detectedStructure()
                    : CentreMonetiqueStructureType.AUTO.name());
            if ((batch.getRib() == null || batch.getRib().isBlank()) && payload.extractedRib() != null && !payload.extractedRib().isBlank()) {
                batch.setRib(payload.extractedRib());
            }
            persistRows(batch, rows, payload.summaryTotals());
            batch.setStatus("PROCESSED");
            batch = batchRepository.save(batch);
            return toDetailDTO(batch, rows, true);
        } catch (Exception e) {
            batch.setStatus("ERROR");
            batch.setErrorMessage(limitError(e.getMessage()));
            batch = batchRepository.save(batch);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Optional<CentreMonetiqueBatchDetailDTO> findDetail(Long id, boolean includeRawOcr) {
        return batchRepository.findById(id)
                .map(batch -> {
                    List<CentreMonetiqueExtractionRow> rows = toRows(transactionRepository.findByBatchIdOrderByRowIndexAsc(batch.getId()));
                    return toDetailDTO(batch, rows, includeRawOcr);
                });
    }

    @Transactional(readOnly = true)
    public List<CentreMonetiqueBatchSummaryDTO> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return batchRepository.findTop200ByOrderByCreatedAtDesc().stream()
                .limit(safeLimit)
                .map(this::toSummaryDTO)
                .toList();
    }

    /** Met à jour uniquement le RIB d'un batch existant. */
    @Transactional
    public Optional<CentreMonetiqueBatchDetailDTO> updateRib(Long id, String rib) {
        Optional<CentreMonetiqueBatch> optional = batchRepository.findById(id);
        if (optional.isEmpty()) {
            return Optional.empty();
        }
        CentreMonetiqueBatch batch = optional.get();
        batch.setRib(rib != null && !rib.isBlank() ? rib.trim() : null);
        CentreMonetiqueBatch saved = batchRepository.save(batch);
        List<CentreMonetiqueExtractionRow> rows = toRows(transactionRepository.findByBatchIdOrderByRowIndexAsc(saved.getId()));
        return Optional.of(toDetailDTO(saved, rows, false));
    }

    @Transactional
    public Optional<CentreMonetiqueBatchDetailDTO> reprocess(Long id,
                                                             Integer year,
                                                             CentreMonetiqueStructureType structureType) throws Exception {
        Optional<CentreMonetiqueBatch> optional = batchRepository.findById(id);
        if (optional.isEmpty()) {
            return Optional.empty();
        }

        CentreMonetiqueBatch batch = optional.get();
        if (batch.getFileData() == null || batch.getFileData().length == 0) {
            throw new IllegalStateException("Fichier source introuvable pour retraitement");
        }

        batch.setStatus("PROCESSING");
        batch.setErrorMessage(null);
        transactionRepository.deleteByBatchId(batch.getId());

        CentreMonetiqueStructureType effectiveStructure = structureType != null
                ? structureType
                : CentreMonetiqueStructureType.fromNullable(batch.getStructure());
        batch.setStructure(effectiveStructure.name());

        CentreMonetiqueExtractionService.ExtractionPayload payload = extractionService.extract(
                batch.getFileData(),
                batch.getOriginalName(),
                year,
                effectiveStructure);
        List<CentreMonetiqueExtractionRow> rows = payload.rows();
        batch.setRawOcrText(payload.rawOcrText());
        String resolvedStructure = payload.detectedStructure() != null && !payload.detectedStructure().isBlank()
                ? payload.detectedStructure()
                : CentreMonetiqueStructureType.AUTO.name();
        batch.setStructure(resolvedStructure);
        // AMEX documents never contain Moroccan RIBs — clear any previously auto-extracted false RIB.
        // A manually-set RIB (user intentionally entered via UI) is not affected here since reprocess
        // cannot distinguish manual vs auto; the user can re-enter a real RIB after reprocess if needed.
        if (CentreMonetiqueStructureType.AMEX.name().equals(resolvedStructure)) {
            batch.setRib(null);
        } else if ((batch.getRib() == null || batch.getRib().isBlank()) && payload.extractedRib() != null && !payload.extractedRib().isBlank()) {
            batch.setRib(payload.extractedRib());
        }
        persistRows(batch, rows, payload.summaryTotals());

        batch.setStatus("PROCESSED");
        CentreMonetiqueBatch saved = batchRepository.save(batch);
        return Optional.of(toDetailDTO(saved, rows, true));
    }

    @Transactional
    public Optional<CentreMonetiqueBatchDetailDTO> saveRows(Long id, List<CentreMonetiqueExtractionRow> rows) {
        Optional<CentreMonetiqueBatch> optional = batchRepository.findById(id);
        if (optional.isEmpty()) {
            return Optional.empty();
        }
        CentreMonetiqueBatch batch = optional.get();
        transactionRepository.deleteByBatchId(batch.getId());
        List<CentreMonetiqueExtractionRow> safeRows = rows != null ? rows : List.of();
        persistRows(batch, safeRows, null);
        batch.setStatus("PROCESSED");
        CentreMonetiqueBatch saved = batchRepository.save(batch);
        return Optional.of(toDetailDTO(saved, safeRows, true));
    }

    /**
     * Rapprochement CMI : liaison par numéro TPE.
     * Chaque bloc "ACHAT REMISE TPE N° :000285" dans le CM correspond à
     * "VENTE PAR CARTE  000285" dans le relevé bancaire.
     * Le montant CM à comparer est le SOLDE NET REMISE du bloc.
     * Fallback sur correspondance par date si les données ne contiennent pas de lignes REMISE ACHAT.
     */
    @Transactional(readOnly = true)
    public Optional<RapprochementResultDTO> rapprochement(Long batchId) {
        Optional<CentreMonetiqueBatch> optional = batchRepository.findById(batchId);
        if (optional.isEmpty()) {
            return Optional.empty();
        }
        CentreMonetiqueBatch batch = optional.get();
        String rib = batch.getRib();
        List<CentreMonetiqueTransaction> cmTxs = transactionRepository.findByBatchIdOrderByRowIndexAsc(batchId);

        if (cmTxs.isEmpty()) {
            return Optional.of(new RapprochementResultDTO(batchId, rib, 0, 0, List.of()));
        }

        // Nombre réel de transactions (hors lignes d'en-tête et totaux)
        int realTxCount = batch.getTransactionCount() != null && batch.getTransactionCount() > 0
                ? batch.getTransactionCount()
                : (int) cmTxs.stream().filter(tx -> {
                    String s = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);
                    return !s.startsWith("TOTAL") && !s.startsWith("SOLDE NET")
                            && !s.equals("REMISE ACHAT") && !s.startsWith("REGLEMENT META")
                            && !s.startsWith("REGLEMENT TOTALS")
                            && !s.equals("AMEX SETTLEMENT") && !s.equals("AMEX TERMINAL")
                            && !s.equals("AMEX TOTAL TERMINAL") && !s.equals("AMEX SUB TOTAL");
                }).count();

        // Détecter si les données sont au nouveau format (avec lignes REMISE ACHAT contenant le TPE).
        boolean hasRemiseAchat = cmTxs.stream()
                .anyMatch(tx -> "REMISE ACHAT".equalsIgnoreCase(nvl(tx.getSection()).trim()));

        boolean isBarid = CentreMonetiqueStructureType.BARID_BANK.name()
                .equalsIgnoreCase(nvl(batch.getStructure()).trim());

        Optional<RapprochementResultDTO> result;
        if (isBarid) {
            result = buildBaridRapprochement(batchId, rib, cmTxs, batch.getRawOcrText());
        } else if (hasRemiseAchat) {
            result = buildTpeBasedRapprochement(batchId, rib, cmTxs);
        } else {
            result = buildDateBasedRapprochement(batchId, rib, cmTxs);
        }

        // Remplacer totalCmTransactions par le vrai comptage (sans les lignes d'en-tête/totaux)
        return result.map(r -> new RapprochementResultDTO(
                r.getBatchId(), r.getBatchRib(), realTxCount, r.getMatchedCount(), r.getMatches()));
    }

    /**
     * Rapprochement CMI par numéro TPE (nouveau format).
     * Groupe les transactions par bloc REMISE ACHAT, lie au relevé bancaire via "VENTE PAR CARTE {tpe}".
     */
    private Optional<RapprochementResultDTO> buildTpeBasedRapprochement(
            Long batchId, String rib, List<CentreMonetiqueTransaction> cmTxs) {

        // Charger les transactions bancaires "VENTE PAR CARTE" pour ce RIB.
        Map<String, BankTransaction> bankByTpe = new LinkedHashMap<>();
        if (rib != null && !rib.isBlank()) {
            List<BankTransaction> bankTxs = bankTransactionRepository.findByRibAndLibelleVenteParCarte(rib);
            for (BankTransaction bt : bankTxs) {
                String tpe = extractTpeFromBankLibelle(nvl(bt.getLibelle()));
                if (tpe != null && !tpe.isBlank()) {
                    bankByTpe.putIfAbsent(tpe, bt);
                }
            }
        }

        // Parcourir les lignes CM en machine d'état : bloc = REMISE ACHAT → transactions → SOLDE NET REMISE.
        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;

        String currentTpe = null;
        String currentHeaderDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();
        BigDecimal currentSoldeNet = null;

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);

            if (section.equals("REMISE ACHAT")) {
                // Fermer le bloc précédent
                if (currentTpe != null) {
                    matchedCount += flushTpeBlock(currentTpe, currentHeaderDate, currentBlockTxs,
                            currentSoldeNet, bankByTpe, matches);
                }
                currentTpe = nvl(tx.getReference()).trim();
                currentHeaderDate = nvl(tx.getDate()).trim();
                currentBlockTxs = new ArrayList<>();
                currentSoldeNet = null;
            } else if (section.startsWith("REMISE ") && !section.equals("REMISE ACHAT")) {
                currentBlockTxs.add(tx);
            } else if (section.equals("SOLDE NET REMISE")) {
                currentSoldeNet = tx.getCredit();
            }
        }
        // Fermer le dernier bloc
        if (currentTpe != null) {
            matchedCount += flushTpeBlock(currentTpe, currentHeaderDate, currentBlockTxs,
                    currentSoldeNet, bankByTpe, matches);
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    /**
     * Construit les lignes de correspondance pour un bloc CMI (un TPE terminal).
     * Retourne le nombre de transactions CM ajoutées comme appariées.
     */
    private int flushTpeBlock(String tpe,
                               String headerDate,
                               List<CentreMonetiqueTransaction> blockTxs,
                               BigDecimal soldeNet,
                               Map<String, BankTransaction> bankByTpe,
                               List<RapprochementResultDTO.RapprochementMatchDTO> matches) {
        if (blockTxs.isEmpty()) {
            return 0;
        }

        int count = blockTxs.size();
        // Référence du groupe : TPE terminal
        String cmRef = "TPE N° " + tpe;
        // Montant CM = SOLDE NET REMISE du bloc (ce qui arrive sur le compte bancaire)
        String cmMontant = toAmount(soldeNet);

        // Liaison bancaire par TPE
        BankTransaction bankTx = bankByTpe.get(tpe);
        String bankStatementName = "";
        String bankMontant = "";
        String bankLibelle = "";
        if (bankTx != null) {
            bankStatementName = bankTx.getStatement() != null
                    ? nvl(bankTx.getStatement().getOriginalName()) : "";
            bankMontant = toAmount(bankTx.getCredit());
            bankLibelle = nvl(bankTx.getLibelle());
        }

        // Date : depuis l'en-tête si disponible, sinon date de la première transaction
        String date = (headerDate != null && !headerDate.isBlank())
                ? headerDate
                : nvl(blockTxs.get(0).getDate());

        for (CentreMonetiqueTransaction tx : blockTxs) {
            matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                    date,
                    cmRef,
                    cmMontant,
                    nvl(tx.getReference()),
                    nvl(tx.getDcFlag()),
                    toAmount(tx.getMontant()),
                    bankStatementName,
                    bankMontant,
                    bankLibelle));
        }
        return bankTx != null ? count : 0;
    }

    /** Extrait le numéro TPE depuis un libellé bancaire "VENTE PAR CARTE  000285". */
    private String extractTpeFromBankLibelle(String libelle) {
        if (libelle == null || libelle.isBlank()) {
            return null;
        }
        Matcher m = BANK_TPE_PATTERN.matcher(libelle);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Rapprochement BARID BANK : liaison par montant de règlement + code commerçant ACQ.
     * Chaque bloc REGLEMENT dans le CM correspond à un virement bancaire unique :
     *   - Montant de règlement (CM) == Crédit (relevé bancaire)
     *   - Code commerçant "COMMERCANT :" (CM) == ACQ{code} dans le libellé bancaire
     */
    private Optional<RapprochementResultDTO> buildBaridRapprochement(
            Long batchId, String rib, List<CentreMonetiqueTransaction> cmTxs, String rawOcrText) {

        // Extraire le code commerçant depuis le texte OCR ("COMMERCANT : 86097")
        String merchantCode = extractBaridMerchantCode(rawOcrText);

        // Charger les transactions bancaires BARID CASH pour ce RIB
        List<BankTransaction> bankTxs = List.of();
        if (rib != null && !rib.isBlank()) {
            bankTxs = bankTransactionRepository.findByRibAndLibelleBaridCash(rib);
        }

        // Construire la table de lookup : clé = montant crédit normalisé -> transaction bancaire
        // Si le code commerçant est connu, filtrer aussi par ACQ{merchantCode} dans le libellé
        Map<String, BankTransaction> bankByAmount = new LinkedHashMap<>();
        for (BankTransaction bt : bankTxs) {
            if (bt.getCredit() == null) continue;
            boolean acqMatches = merchantCode == null || merchantCode.isBlank()
                    || nvl(bt.getLibelle()).toUpperCase(Locale.ROOT).contains("ACQ" + merchantCode);
            if (acqMatches) {
                bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
            }
        }
        // Fallback sans filtre commerçant si aucun match n'a été trouvé par code
        if (bankByAmount.isEmpty() && !bankTxs.isEmpty()) {
            for (BankTransaction bt : bankTxs) {
                if (bt.getCredit() != null) {
                    bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
                }
            }
        }

        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;

        // Ordre réel des lignes dans l'extraction BARID_BANK pour un bloc :
        //   1. REGLEMENT {id}  (transactions individuelles)
        //   2. REGLEMENT META  (date du règlement — String tx.getDate())
        //   3. REGLEMENT TOTALS (montant de règlement — BigDecimal tx.getMontant(),
        //                        id du règlement    — String tx.getReference())
        //   4. TOTAL REMISE / SOLDE NET REMISE (totaux — ignorés ici)
        // => flush déclenché à la réception de REGLEMENT TOTALS.
        String currentDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);

            if (section.equals("REGLEMENT META")) {
                // Sauvegarder la date (String)
                String d = nvl(tx.getDate());
                if (!d.isBlank()) currentDate = d;

            } else if (section.equals("REGLEMENT TOTALS")) {
                // Montant de règlement (BigDecimal) et id (String reference)
                BigDecimal montantReglement = tx.getMontant();
                String reglementId = nvl(tx.getReference());
                if (!currentBlockTxs.isEmpty()) {
                    matchedCount += flushBaridBlock(currentDate, reglementId,
                            montantReglement, currentBlockTxs, bankByAmount, matches);
                }
                // Réinitialiser pour le bloc suivant
                currentDate = null;
                currentBlockTxs = new ArrayList<>();

            } else if (section.startsWith("REGLEMENT ")
                    && !section.equals("REGLEMENT META")
                    && !section.equals("REGLEMENT TOTALS")) {
                currentBlockTxs.add(tx);
            }
        }
        // Flush du dernier bloc si REGLEMENT TOTALS absent (données incomplètes)
        if (!currentBlockTxs.isEmpty()) {
            matchedCount += flushBaridBlock(currentDate, "",
                    null, currentBlockTxs, bankByAmount, matches);
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    /**
     * Construit les lignes de correspondance pour un bloc BARID BANK (un REGLEMENT).
     * Retourne le nombre de transactions CM ajoutées comme appariées.
     */
    private int flushBaridBlock(String date,
                                 String reglementId,
                                 BigDecimal montantReglement,
                                 List<CentreMonetiqueTransaction> blockTxs,
                                 Map<String, BankTransaction> bankByAmount,
                                 List<RapprochementResultDTO.RapprochementMatchDTO> matches) {
        if (blockTxs.isEmpty()) return 0;

        int count = blockTxs.size();
        String cmRef = count == 1
                ? ("REGLEMENT " + reglementId)
                : (count + " transactions CM");
        String cmMontant = toAmount(montantReglement);

        // Trouver la transaction bancaire correspondante par montant de règlement
        BankTransaction bankTx = montantReglement != null
                ? bankByAmount.get(amountKey(montantReglement))
                : null;

        String bankStatementName = "";
        String bankMontant = "";
        String bankLibelle = "";
        if (bankTx != null) {
            bankStatementName = bankTx.getStatement() != null
                    ? nvl(bankTx.getStatement().getOriginalName()) : "";
            bankMontant = toAmount(bankTx.getCredit());
            bankLibelle = nvl(bankTx.getLibelle());
        }

        String blockDate = (date != null && !date.isBlank())
                ? date
                : nvl(blockTxs.get(0).getDate());

        for (CentreMonetiqueTransaction tx : blockTxs) {
            matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                    blockDate,
                    cmRef,
                    cmMontant,
                    nvl(tx.getReference()),
                    nvl(tx.getDcFlag()),
                    toAmount(tx.getMontant()),
                    bankStatementName,
                    bankMontant,
                    bankLibelle));
        }
        return bankTx != null ? count : 0;
    }

    /** Extrait le code commerçant BARID BANK depuis le texte OCR ("COMMERCANT : 86097"). */
    private String extractBaridMerchantCode(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) return null;
        Matcher m = BARID_COMMERCANT_CODE_PATTERN.matcher(rawOcrText);
        return m.find() ? m.group(1) : null;
    }

    /** Clé normalisée pour comparer des montants (2 décimales fixes). */
    private String amountKey(BigDecimal amount) {
        if (amount == null) return "";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Rapprochement par date (fallback pour CMI sans lignes REMISE ACHAT).
     */
    private Optional<RapprochementResultDTO> buildDateBasedRapprochement(
            Long batchId, String rib, List<CentreMonetiqueTransaction> cmTxs) {

        Map<LocalDate, List<CentreMonetiqueTransaction>> cmByDate = new LinkedHashMap<>();
        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = tx.getSection() == null ? "" : tx.getSection().trim().toUpperCase(Locale.ROOT);
            boolean isDetailSection = section.equals("REMISE")
                    || section.startsWith("REGLEMENT ")
                    || (section.startsWith("AMEX ") && !section.equals("AMEX SETTLEMENT")
                            && !section.equals("AMEX TERMINAL") && !section.equals("AMEX TOTAL TERMINAL")
                            && !section.equals("AMEX SUB TOTAL"));
            if (!isDetailSection) {
                continue;
            }
            LocalDate d = parseRowDate(tx.getDate());
            if (d == null) {
                continue;
            }
            cmByDate.computeIfAbsent(d, k -> new ArrayList<>()).add(tx);
        }

        if (cmByDate.isEmpty()) {
            return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), 0, List.of()));
        }

        Map<LocalDate, List<BankTransaction>> bankByDate = new HashMap<>();
        if (rib != null && !rib.isBlank()) {
            List<BankTransaction> bankTxs = bankTransactionRepository
                    .findByStatementRibAndDateOperationIn(rib, cmByDate.keySet());
            for (BankTransaction bt : bankTxs) {
                if (bt.getDateOperation() != null) {
                    bankByDate.computeIfAbsent(bt.getDateOperation(), k -> new ArrayList<>()).add(bt);
                }
            }
        }

        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;
        for (Map.Entry<LocalDate, List<CentreMonetiqueTransaction>> entry : cmByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<CentreMonetiqueTransaction> txsForDate = entry.getValue();

            int cmCount = txsForDate.size();
            BigDecimal cmTotal = txsForDate.stream()
                    .map(CentreMonetiqueTransaction::getMontant)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String cmRef = cmCount == 1
                    ? nvl(txsForDate.get(0).getReference())
                    : cmCount + " transactions CM";
            String cmTotalStr = toAmount(cmTotal);

            List<BankTransaction> bankForDate = bankByDate.getOrDefault(date, List.of());
            String bankStatementName = "";
            String bankMontant = "";
            String bankLibelle = "";
            if (!bankForDate.isEmpty()) {
                matchedCount += txsForDate.size();
                bankStatementName = bankForDate.stream()
                        .map(bt -> bt.getStatement() != null ? nvl(bt.getStatement().getOriginalName()) : "")
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .collect(Collectors.joining(" | "));
                BigDecimal bankTotal = bankForDate.stream()
                        .map(BankTransaction::getCredit)
                        .filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                bankMontant = toAmount(bankTotal);
                bankLibelle = bankForDate.stream()
                        .map(bt -> nvl(bt.getLibelle()))
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .collect(Collectors.joining(" / "));
            }

            for (CentreMonetiqueTransaction tx : txsForDate) {
                matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                        date.toString(),
                        cmRef,
                        cmTotalStr,
                        nvl(tx.getReference()),
                        nvl(tx.getDcFlag()),
                        toAmount(tx.getMontant()),
                        bankStatementName,
                        bankMontant,
                        bankLibelle));
            }
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    @Transactional
    public boolean delete(Long id) {
        Optional<CentreMonetiqueBatch> optional = batchRepository.findById(id);
        if (optional.isEmpty()) {
            return false;
        }
        batchRepository.delete(optional.get());
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> filePayload(Long id) {
        return batchRepository.findById(id)
                .map(batch -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("filename", batch.getFilename());
                    payload.put("contentType",
                            batch.getFileContentType() != null ? batch.getFileContentType() : "application/octet-stream");
                    payload.put("data", batch.getFileData());
                    return payload;
                });
    }

    private void persistRows(CentreMonetiqueBatch batch,
                             List<CentreMonetiqueExtractionRow> rows,
                             CentreMonetiqueExtractionService.SummaryTotals summaryTotals) {
        batch.getTransactions().clear();

        BigDecimal totalMontant = BigDecimal.ZERO;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        int txCount = 0;

        int i = 1;
        for (CentreMonetiqueExtractionRow row : rows) {
            CentreMonetiqueTransaction tx = new CentreMonetiqueTransaction();
            tx.setRowIndex(i++);
            tx.setSection(trimTo(row.getSection(), 64));
            tx.setDate(trimTo(row.getDate(), 16));
            tx.setReference(trimTo(row.getReference(), 32));
            tx.setDcFlag(trimTo(row.getDc(), 16));
            tx.setMontant(parseDecimal(row.getMontant()));
            tx.setDebit(parseDecimal(row.getDebit()));
            tx.setCredit(parseDecimal(row.getCredit()));
            batch.addTransaction(tx);

            String section = row.getSection() == null ? "" : row.getSection().trim().toUpperCase(Locale.ROOT);
            boolean summaryRow = section.startsWith("TOTAL")
                    || section.startsWith("SOLDE NET REMISE")
                    || section.startsWith("REGLEMENT META")
                    || section.equals("REMISE ACHAT")
                    || section.equals("AMEX SETTLEMENT")
                    || section.equals("AMEX TERMINAL")
                    || section.equals("AMEX TOTAL TERMINAL")
                    || section.equals("AMEX SUB TOTAL");
            if (summaryRow) {
                if (tx.getMontant() != null) {
                    totalMontant = totalMontant.add(tx.getMontant());
                }
                if (tx.getDebit() != null) {
                    totalDebit = totalDebit.add(tx.getDebit());
                }
                if (tx.getCredit() != null) {
                    totalCredit = totalCredit.add(tx.getCredit());
                }
            } else {
                txCount++;
            }
        }

        if (summaryTotals == null || summaryTotals.totalRemises() == null) {
            for (CentreMonetiqueExtractionRow row : rows) {
                if ("REMISE".equalsIgnoreCase(row.getSection())) {
                    BigDecimal amount = parseDecimal(row.getMontant());
                    if (amount != null) {
                        totalMontant = totalMontant.add(amount);
                    }
                }
            }
        }

        BigDecimal summaryRemises = summaryTotals != null ? summaryTotals.totalRemises() : null;
        BigDecimal summaryCommissionHt = summaryTotals != null ? summaryTotals.totalCommissionsHt() : null;
        BigDecimal summaryTva = summaryTotals != null ? summaryTotals.totalTvaSurCommissions() : null;
        BigDecimal summarySoldeNet = summaryTotals != null ? summaryTotals.soldeNetRemise() : null;
        BigDecimal summaryDebitNet = sumNullable(summaryCommissionHt, summaryTva);

        if (summaryRemises != null) {
            totalMontant = summaryRemises;
        }
        if (summaryDebitNet != null) {
            totalDebit = summaryDebitNet;
        }
        if (summarySoldeNet != null) {
            totalCredit = summarySoldeNet;
        }

        batch.setTransactionCount(txCount);
        batch.setStatementPeriod(deriveStatementPeriod(rows));
        batch.setTotalMontant(scale2(totalMontant));
        batch.setTotalDebit(scale2(totalDebit));
        batch.setTotalCredit(scale2(totalCredit));
        batch.setTotalCommissionHt(scale2(summaryCommissionHt));
        batch.setTotalTvaSurCommissions(scale2(summaryTva));
        batch.setSoldeNetRemise(scale2(summarySoldeNet != null ? summarySoldeNet : totalCredit));
    }

    private CentreMonetiqueBatchSummaryDTO toSummaryDTO(CentreMonetiqueBatch batch) {
        boolean isLinked = batch.getRib() != null && !batch.getRib().isBlank()
                && bankStatementRepository.countByRib(batch.getRib()) > 0;
        return new CentreMonetiqueBatchSummaryDTO(
                batch.getId(),
                batch.getFilename(),
                batch.getOriginalName(),
                nvl(batch.getRib()),
                batch.getStatus(),
                nvl(batch.getStructure()),
                nvl(batch.getStatementPeriod()),
                String.valueOf(batch.getTransactionCount()),
                toAmount(batch.getTotalMontant()),
                toAmount(batch.getTotalCommissionHt()),
                toAmount(batch.getTotalTvaSurCommissions()),
                toAmount(batch.getSoldeNetRemise()),
                toAmount(batch.getTotalDebit()),
                toAmount(batch.getTotalCredit()),
                batch.getTransactionCount(),
                toDateTime(batch.getCreatedAt()),
                toDateTime(batch.getUpdatedAt()),
                batch.getErrorMessage(),
                isLinked);
    }

    private CentreMonetiqueBatchDetailDTO toDetailDTO(CentreMonetiqueBatch batch,
                                                       List<CentreMonetiqueExtractionRow> rows,
                                                       boolean includeRawOcr) {
        return new CentreMonetiqueBatchDetailDTO(
                batch.getId(),
                batch.getFilename(),
                batch.getOriginalName(),
                nvl(batch.getRib()),
                batch.getStatus(),
                nvl(batch.getStructure()),
                nvl(batch.getStatementPeriod()),
                batch.getFileContentType(),
                batch.getFileSize(),
                String.valueOf(batch.getTransactionCount()),
                toAmount(batch.getTotalMontant()),
                toAmount(batch.getTotalCommissionHt()),
                toAmount(batch.getTotalTvaSurCommissions()),
                toAmount(batch.getSoldeNetRemise()),
                toAmount(batch.getTotalDebit()),
                toAmount(batch.getTotalCredit()),
                batch.getTransactionCount(),
                toDateTime(batch.getCreatedAt()),
                toDateTime(batch.getUpdatedAt()),
                batch.getErrorMessage(),
                includeRawOcr ? batch.getRawOcrText() : null,
                rows);
    }

    private List<CentreMonetiqueExtractionRow> toRows(List<CentreMonetiqueTransaction> transactions) {
        List<CentreMonetiqueExtractionRow> rows = new ArrayList<>();
        for (CentreMonetiqueTransaction tx : transactions) {
            rows.add(new CentreMonetiqueExtractionRow(
                    nvl(tx.getSection()),
                    nvl(tx.getDate()),
                    nvl(tx.getReference()),
                    toAmount(tx.getMontant()),
                    toAmount(tx.getDebit()),
                    toAmount(tx.getCredit()),
                    nvl(tx.getDcFlag())));
        }
        return rows;
    }

    private String toDateTime(LocalDateTime value) {
        return value == null ? "" : DATETIME_FMT.format(value);
    }

    private String toAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0, RoundingMode.HALF_UP);
        }
        return normalized.toPlainString();
    }

    private BigDecimal parseDecimal(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().replaceAll("\\s+", "");
        normalized = normalized.replace(',', '.');
        try {
            return new BigDecimal(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal scale2(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumNullable(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return null;
        }
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.add(right);
    }

    private String deriveStatementPeriod(List<CentreMonetiqueExtractionRow> rows) {
        LocalDate min = null;
        LocalDate max = null;
        for (CentreMonetiqueExtractionRow row : rows) {
            String section = row.getSection() == null ? "" : row.getSection().trim().toUpperCase(Locale.ROOT);
            boolean isDetailRow = "REMISE".equals(section)
                    || (section.startsWith("REMISE ") && !section.equals("REMISE ACHAT"))
                    || section.startsWith("REGLEMENT ")
                    || (section.startsWith("AMEX ") && !section.equals("AMEX SETTLEMENT")
                            && !section.equals("AMEX TERMINAL") && !section.equals("AMEX TOTAL TERMINAL")
                            && !section.equals("AMEX SUB TOTAL"));
            if (!isDetailRow || section.startsWith("REGLEMENT META")) {
                continue;
            }
            LocalDate parsed = parseRowDate(row.getDate());
            if (parsed == null) {
                continue;
            }
            if (min == null || parsed.isBefore(min)) {
                min = parsed;
            }
            if (max == null || parsed.isAfter(max)) {
                max = parsed;
            }
        }
        if (min == null || max == null) {
            return "";
        }
        String minPeriod = String.format("%02d/%04d", min.getMonthValue(), min.getYear());
        String maxPeriod = String.format("%02d/%04d", max.getMonthValue(), max.getYear());
        if (minPeriod.equals(maxPeriod)) {
            return minPeriod;
        }
        return minPeriod + " - " + maxPeriod;
    }

    private LocalDate parseRowDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        int firstSpace = normalized.indexOf(' ');
        if (firstSpace > 0) {
            normalized = normalized.substring(0, firstSpace);
        }
        String[] parts = normalized.split("/");
        if (parts.length != 3) {
            return null;
        }
        try {
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int year = Integer.parseInt(parts[2]);
            year = year < 100 ? 2000 + year : year;
            return LocalDate.of(year, month, day);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String trimTo(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() <= maxLen) {
            return v;
        }
        return v.substring(0, maxLen);
    }

    private String limitError(String message) {
        if (message == null || message.isBlank()) {
            return "Erreur inconnue";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
