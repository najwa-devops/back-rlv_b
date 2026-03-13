package com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.service;

import com.example.releve_bancaire.centre_monetique.entity.CentreMonetiqueBatch;
import com.example.releve_bancaire.centre_monetique.entity.CentreMonetiqueTransaction;
import com.example.releve_bancaire.centre_monetique.repository.CentreMonetiqueBatchRepository;
import com.example.releve_bancaire.centre_monetique.repository.CentreMonetiqueTransactionRepository;
import com.example.releve_bancaire.centre_monetique.service.CentreMonetiqueStructureType;
import com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.dto.CmExpansionDTO;
import com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.dto.CmExpansionLineDTO;
import com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.dto.RapprochementResultDTO;
import com.example.releve_bancaire.releve_bancaire.entity.BankStatement;
import com.example.releve_bancaire.releve_bancaire.entity.BankTransaction;
import com.example.releve_bancaire.releve_bancaire.repository.BankStatementRepository;
import com.example.releve_bancaire.releve_bancaire.repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CentreMonetiqueLiaisonService {

    /** Extrait le numero TPE depuis un libelle bancaire "VENTE PAR CARTE  000285". */
    private static final Pattern BANK_TPE_PATTERN = Pattern.compile(
            "VENTE\\s+PAR\\s+CARTE\\s+([A-Z0-9]{4,10})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extrait le code TPE depuis les derniers chiffres d'un libelle bancaire.
     * Couvre : "AZAR RESTAURAN294055", "ATTEATUDE CAF000294", "AZAR REST MARRAKEC000028".
     * Le TPE est toujours les 5 ou 6 derniers chiffres du libelle.
     */
    private static final Pattern BANK_TPE_TRAILING_PATTERN = Pattern.compile(
            "([0-9]{5,6})\\s*$");

    /** Extrait le code commercant depuis le texte OCR BARID_BANK "COMMERCANT : 86097". */
    private static final Pattern BARID_COMMERCANT_CODE_PATTERN = Pattern.compile(
            "(?i)COMMERCANT\\s*[:\\-]?\\s*(?:[^\\d\\n]{0,40})?([0-9]{4,10})");

    private final CentreMonetiqueBatchRepository batchRepository;
    private final CentreMonetiqueTransactionRepository transactionRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final BankStatementRepository bankStatementRepository;

    /**
     * Rapprochement CMI : liaison par numero TPE.
     * Chaque bloc "ACHAT REMISE TPE N° :000285" dans le CM correspond a
     * "VENTE PAR CARTE  000285" dans le releve bancaire.
     * Le montant CM a comparer est le SOLDE NET REMISE du bloc.
     * Fallback sur correspondance par date si les donnees ne contiennent pas de lignes REMISE ACHAT.
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

        // Nombre reel de transactions (hors lignes d'en-tete et totaux)
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

        // Detecter si les donnees sont au nouveau format (avec lignes REMISE ACHAT contenant le TPE).
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

        // Remplacer totalCmTransactions par le vrai comptage (sans les lignes d'en-tete/totaux)
        return result.map(r -> new RapprochementResultDTO(
                r.getBatchId(), r.getBatchRib(), realTxCount, r.getMatchedCount(), r.getMatches()));
    }

    /**
     * Rapprochement CMI par numero TPE (nouveau format).
     * Groupe les transactions par bloc REMISE ACHAT, lie au releve bancaire via "VENTE PAR CARTE {tpe}".
     */
    private Optional<RapprochementResultDTO> buildTpeBasedRapprochement(
            Long batchId, String rib, List<CentreMonetiqueTransaction> cmTxs) {

        // Charger TOUTES les transactions credit pour ce RIB.
        // Lookup strict : tpe -> Map<amountKey, BankTransaction>.
        // La liaison necessite TPE identique ET montant SOLDE NET REMISE == credit bancaire.
        Map<String, Map<String, BankTransaction>> bankByTpeAndAmount = new LinkedHashMap<>();
        if (rib != null && !rib.isBlank()) {
            List<BankTransaction> bankTxs = bankTransactionRepository.findCreditTransactionsByRib(rib);
            for (BankTransaction bt : bankTxs) {
                String tpe = extractTpeFromBankLibelle(nvl(bt.getLibelle()));
                if (tpe != null && !tpe.isBlank() && bt.getCredit() != null) {
                    bankByTpeAndAmount
                            .computeIfAbsent(tpe, k -> new LinkedHashMap<>())
                            .putIfAbsent(amountKey(bt.getCredit()), bt);
                }
            }
        }

        // Parcourir les lignes CM en machine d'etat : bloc = REMISE ACHAT -> transactions -> SOLDE NET REMISE.
        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;

        String currentTpe = null;
        String currentHeaderDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();
        BigDecimal currentSoldeNet = null;

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);

            if (section.equals("REMISE ACHAT")) {
                // Fermer le bloc precedent
                if (currentTpe != null) {
                    matchedCount += flushTpeBlock(currentTpe, currentHeaderDate, currentBlockTxs,
                            currentSoldeNet, bankByTpeAndAmount, matches);
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
                    currentSoldeNet, bankByTpeAndAmount, matches);
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    /**
     * Construit les lignes de correspondance pour un bloc CMI (un TPE terminal).
     * Liaison stricte : TPE identique ET montant SOLDE NET REMISE == credit bancaire.
     * Retourne le nombre de transactions CM ajoutees comme appariees.
     */
    private int flushTpeBlock(String tpe,
                               String headerDate,
                               List<CentreMonetiqueTransaction> blockTxs,
                               BigDecimal soldeNet,
                               Map<String, Map<String, BankTransaction>> bankByTpeAndAmount,
                               List<RapprochementResultDTO.RapprochementMatchDTO> matches) {
        if (blockTxs.isEmpty()) {
            return 0;
        }

        int count = blockTxs.size();
        // Reference du groupe : TPE terminal
        String cmRef = "TPE N° " + tpe;
        // Montant CM = SOLDE NET REMISE du bloc (ce qui arrive sur le compte bancaire)
        String cmMontant = toAmount(soldeNet);

        // Liaison stricte : TPE + montant SOLDE NET REMISE doivent etre identiques.
        // Aucun fallback — si les montants different, pas de liaison bancaire.
        BankTransaction bankTx = null;
        if (soldeNet != null && bankByTpeAndAmount.containsKey(tpe)) {
            bankTx = bankByTpeAndAmount.get(tpe).get(amountKey(soldeNet));
        }
        String bankStatementName = "";
        String bankMontant = "";
        String bankLibelle = "";
        if (bankTx != null) {
            bankStatementName = bankTx.getStatement() != null
                    ? nvl(bankTx.getStatement().getOriginalName()) : "";
            bankMontant = toAmount(bankTx.getCredit());
            bankLibelle = nvl(bankTx.getLibelle());
        }

        // Date : depuis l'en-tete si disponible, sinon date de la premiere transaction
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

    /**
     * Extrait le numero TPE depuis un libelle bancaire.
     * Essaie d'abord "VENTE PAR CARTE {tpe}", puis prend les 5-6 derniers chiffres du libelle.
     * Couvre : "VENTE PAR CARTE 000293", "AZAR RESTAURAN294055", "ATTEATUDE CAF000294".
     */
    private String extractTpeFromBankLibelle(String libelle) {
        if (libelle == null || libelle.isBlank()) {
            return null;
        }
        Matcher m = BANK_TPE_PATTERN.matcher(libelle);
        if (m.find()) {
            return m.group(1);
        }
        Matcher m2 = BANK_TPE_TRAILING_PATTERN.matcher(libelle.trim());
        return m2.find() ? m2.group(1) : null;
    }

    /**
     * Rapprochement BARID BANK : liaison par montant de reglement + code commercant ACQ.
     * Chaque bloc REGLEMENT dans le CM correspond a un virement bancaire unique :
     *   - Montant de reglement (CM) == Credit (releve bancaire)
     *   - Code commercant "COMMERCANT :" (CM) == ACQ{code} dans le libelle bancaire
     */
    private Optional<RapprochementResultDTO> buildBaridRapprochement(
            Long batchId, String rib, List<CentreMonetiqueTransaction> cmTxs, String rawOcrText) {

        // Extraire le code commercant depuis le texte OCR ("COMMERCANT : 86097")
        String merchantCode = extractBaridMerchantCode(rawOcrText);

        // Charger les transactions bancaires BARID CASH pour ce RIB
        List<BankTransaction> bankTxs = List.of();
        if (rib != null && !rib.isBlank()) {
            bankTxs = bankTransactionRepository.findByRibAndLibelleBaridCash(rib);
        }

        // Construire la table de lookup : cle = montant credit normalise -> transaction bancaire
        // Si le code commercant est connu, filtrer aussi par ACQ{merchantCode} dans le libelle
        Map<String, BankTransaction> bankByAmount = new LinkedHashMap<>();
        for (BankTransaction bt : bankTxs) {
            if (bt.getCredit() == null) continue;
            boolean acqMatches = merchantCode == null || merchantCode.isBlank()
                    || nvl(bt.getLibelle()).toUpperCase(Locale.ROOT).contains("ACQ" + merchantCode);
            if (acqMatches) {
                bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
            }
        }
        // Fallback sans filtre commercant si aucun match n'a ete trouve par code
        if (bankByAmount.isEmpty() && !bankTxs.isEmpty()) {
            for (BankTransaction bt : bankTxs) {
                if (bt.getCredit() != null) {
                    bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
                }
            }
        }

        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;

        // Ordre reel des lignes dans l'extraction BARID_BANK pour un bloc :
        //   1. REGLEMENT {id}  (transactions individuelles)
        //   2. REGLEMENT META  (date du reglement — String tx.getDate())
        //   3. REGLEMENT TOTALS (montant de reglement — BigDecimal tx.getMontant(),
        //                        id du reglement    — String tx.getReference())
        //   4. TOTAL REMISE / SOLDE NET REMISE (totaux — ignores ici)
        // => flush declenche a la reception de REGLEMENT TOTALS.
        String currentDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);

            if (section.equals("REGLEMENT META")) {
                // Sauvegarder la date (String)
                String d = nvl(tx.getDate());
                if (!d.isBlank()) currentDate = d;

            } else if (section.equals("REGLEMENT TOTALS")) {
                // Montant de reglement (BigDecimal) et id (String reference)
                BigDecimal montantReglement = tx.getMontant();
                String reglementId = nvl(tx.getReference());
                if (!currentBlockTxs.isEmpty()) {
                    matchedCount += flushBaridBlock(currentDate, reglementId,
                            montantReglement, currentBlockTxs, bankByAmount, matches);
                }
                // Reinitialiser pour le bloc suivant
                currentDate = null;
                currentBlockTxs = new ArrayList<>();

            } else if (section.startsWith("REGLEMENT ")
                    && !section.equals("REGLEMENT META")
                    && !section.equals("REGLEMENT TOTALS")) {
                currentBlockTxs.add(tx);
            }
        }
        // Flush du dernier bloc si REGLEMENT TOTALS absent (donnees incompletes)
        if (!currentBlockTxs.isEmpty()) {
            matchedCount += flushBaridBlock(currentDate, "",
                    null, currentBlockTxs, bankByAmount, matches);
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    /**
     * Construit les lignes de correspondance pour un bloc BARID BANK (un REGLEMENT).
     * Retourne le nombre de transactions CM ajoutees comme appariees.
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

        // Trouver la transaction bancaire correspondante par montant de reglement
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

    /** Extrait le code commercant BARID BANK depuis le texte OCR ("COMMERCANT : 86097"). */
    private String extractBaridMerchantCode(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) return null;
        Matcher m = BARID_COMMERCANT_CODE_PATTERN.matcher(rawOcrText);
        return m.find() ? m.group(1) : null;
    }

    /** Cle normalisee pour comparer des montants (2 decimales fixes). */
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

    private LocalDate parseRowDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replace('.', '/').replace('-', '/');
        String[] parts = cleaned.split("/");
        if (parts.length < 3) {
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

    // ==================== CM EXPANSIONS POUR RELEVE BANCAIRE ====================

    /**
     * Retourne pour chaque transaction bancaire du releve donne la liste des lignes CM
     * qui la remplacent dans la vue detail (liaison par RIB, TPE+montant ou montant BARID).
     */
    @Transactional(readOnly = true)
    public List<CmExpansionDTO> getCmExpansionsForStatement(Long statementId) {
        BankStatement statement = bankStatementRepository.findById(statementId).orElse(null);
        if (statement == null) return List.of();

        String rib = statement.getRib();
        if (rib == null || rib.isBlank()) return List.of();

        List<CentreMonetiqueBatch> batches =
                batchRepository.findByRibAndStatusOrderByCreatedAtDesc(rib, "PROCESSED");
        if (batches.isEmpty()) return List.of();

        List<BankTransaction> statementTxs =
                bankTransactionRepository.findByStatementIdOrderByTransactionIndexAsc(statementId);

        List<CmExpansionDTO> result = new ArrayList<>();
        for (CentreMonetiqueBatch batch : batches) {
            List<CentreMonetiqueTransaction> cmTxs =
                    transactionRepository.findByBatchIdOrderByRowIndexAsc(batch.getId());
            if (cmTxs.isEmpty()) continue;

            String structure = nvl(batch.getStructure()).trim().toUpperCase(Locale.ROOT);
            boolean isBarid = CentreMonetiqueStructureType.BARID_BANK.name().equals(structure);
            boolean hasTpe = cmTxs.stream()
                    .anyMatch(t -> "REMISE ACHAT".equalsIgnoreCase(nvl(t.getSection()).trim()));

            if (isBarid) {
                result.addAll(buildBaridExpansions(batch, cmTxs, statementTxs));
            } else if (hasTpe) {
                result.addAll(buildTpeExpansions(batch, cmTxs, statementTxs));
            }
        }
        return result;
    }

    private List<CmExpansionDTO> buildTpeExpansions(CentreMonetiqueBatch batch,
                                                     List<CentreMonetiqueTransaction> cmTxs,
                                                     List<BankTransaction> statementTxs) {
        Map<String, Map<String, BankTransaction>> bankByTpeAndAmount = new LinkedHashMap<>();
        for (BankTransaction bt : statementTxs) {
            if (bt.getCredit() == null) continue;
            String tpe = extractTpeFromBankLibelle(nvl(bt.getLibelle()));
            if (tpe != null && !tpe.isBlank()) {
                bankByTpeAndAmount
                        .computeIfAbsent(tpe, k -> new LinkedHashMap<>())
                        .putIfAbsent(amountKey(bt.getCredit()), bt);
            }
        }

        List<CmExpansionDTO> result = new ArrayList<>();
        String currentTpe = null;
        String currentHeaderDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();
        BigDecimal currentSoldeNet = null;
        BigDecimal currentCommissionHt = null;
        BigDecimal currentTvaSurCommissions = null;

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);
            if (section.equals("REMISE ACHAT")) {
                if (currentTpe != null) {
                    CmExpansionDTO exp = buildTpeExpansionBlock(batch, currentTpe, currentHeaderDate,
                            currentBlockTxs, currentSoldeNet, currentCommissionHt, currentTvaSurCommissions,
                            bankByTpeAndAmount);
                    if (exp != null) result.add(exp);
                }
                currentTpe = nvl(tx.getReference()).trim();
                currentHeaderDate = nvl(tx.getDate()).trim();
                currentBlockTxs = new ArrayList<>();
                currentSoldeNet = null;
                currentCommissionHt = null;
                currentTvaSurCommissions = null;
            } else if (section.startsWith("REMISE ") && !section.equals("REMISE ACHAT")) {
                currentBlockTxs.add(tx);
            } else if (section.equals("TOTAL COMMISSIONS HT")) {
                currentCommissionHt = tx.getDebit();
            } else if (section.equals("TOTAL TVA SUR COMMISSIONS")) {
                currentTvaSurCommissions = tx.getDebit();
            } else if (section.equals("SOLDE NET REMISE")) {
                currentSoldeNet = tx.getCredit();
            }
        }
        if (currentTpe != null) {
            CmExpansionDTO exp = buildTpeExpansionBlock(batch, currentTpe, currentHeaderDate,
                    currentBlockTxs, currentSoldeNet, currentCommissionHt, currentTvaSurCommissions,
                    bankByTpeAndAmount);
            if (exp != null) result.add(exp);
        }
        return result;
    }

    private CmExpansionDTO buildTpeExpansionBlock(CentreMonetiqueBatch batch, String tpe, String headerDate,
                                                   List<CentreMonetiqueTransaction> blockTxs,
                                                   BigDecimal soldeNet,
                                                   BigDecimal commissionHt,
                                                   BigDecimal tvaSurCommissions,
                                                   Map<String, Map<String, BankTransaction>> bankByTpeAndAmount) {
        if (blockTxs.isEmpty() || soldeNet == null) return null;
        Map<String, BankTransaction> byAmount = bankByTpeAndAmount.get(tpe);
        if (byAmount == null) return null;
        BankTransaction bankTx = byAmount.get(amountKey(soldeNet));
        if (bankTx == null) return null;

        String date = (headerDate != null && !headerDate.isBlank()) ? headerDate : nvl(blockTxs.get(0).getDate());
        List<CmExpansionLineDTO> lines = blockTxs.stream()
                .map(t -> new CmExpansionLineDTO(date, nvl(t.getReference()), nvl(t.getDcFlag()), toAmount(t.getMontant())))
                .toList();
        return new CmExpansionDTO(bankTx.getId(), batch.getId(), nvl(batch.getOriginalName()),
                "TPE N° " + tpe, toAmount(soldeNet),
                toAmount(commissionHt), toAmount(tvaSurCommissions),
                lines);
    }

    private List<CmExpansionDTO> buildBaridExpansions(CentreMonetiqueBatch batch,
                                                       List<CentreMonetiqueTransaction> cmTxs,
                                                       List<BankTransaction> statementTxs) {
        String merchantCode = extractBaridMerchantCode(batch.getRawOcrText());

        Map<String, BankTransaction> bankByAmount = new LinkedHashMap<>();
        for (BankTransaction bt : statementTxs) {
            if (bt.getCredit() == null) continue;
            String libelle = nvl(bt.getLibelle()).toUpperCase(Locale.ROOT);
            if (!libelle.contains("BARID CASH")) continue;
            boolean acqOk = merchantCode == null || merchantCode.isBlank()
                    || libelle.contains("ACQ" + merchantCode);
            if (acqOk) bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
        }
        if (bankByAmount.isEmpty()) {
            for (BankTransaction bt : statementTxs) {
                if (bt.getCredit() == null) continue;
                if (!nvl(bt.getLibelle()).toUpperCase(Locale.ROOT).contains("BARID CASH")) continue;
                bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
            }
        }

        List<CmExpansionDTO> result = new ArrayList<>();
        String currentDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);
            if (section.equals("REGLEMENT META")) {
                String d = nvl(tx.getDate());
                if (!d.isBlank()) currentDate = d;
            } else if (section.equals("REGLEMENT TOTALS")) {
                BigDecimal montant = tx.getMontant();
                String reglementId = nvl(tx.getReference());
                if (!currentBlockTxs.isEmpty() && montant != null) {
                    BankTransaction bankTx = bankByAmount.get(amountKey(montant));
                    if (bankTx != null) {
                        String blockDate = (currentDate != null && !currentDate.isBlank())
                                ? currentDate : nvl(currentBlockTxs.get(0).getDate());
                        List<CmExpansionLineDTO> lines = currentBlockTxs.stream()
                                .map(t -> new CmExpansionLineDTO(blockDate, nvl(t.getReference()),
                                        nvl(t.getDcFlag()), toAmount(t.getMontant())))
                                .toList();
                        result.add(new CmExpansionDTO(bankTx.getId(), batch.getId(),
                                nvl(batch.getOriginalName()), "REGLEMENT " + reglementId,
                                toAmount(montant), "", "", lines));
                    }
                }
                currentDate = null;
                currentBlockTxs = new ArrayList<>();
            } else if (section.startsWith("REGLEMENT ")
                    && !section.equals("REGLEMENT META")
                    && !section.equals("REGLEMENT TOTALS")) {
                currentBlockTxs.add(tx);
            }
        }
        return result;
    }

    private String toAmount(BigDecimal amount) {
        if (amount == null) return "";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
