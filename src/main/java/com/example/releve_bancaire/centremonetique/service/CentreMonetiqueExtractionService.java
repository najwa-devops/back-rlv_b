package com.example.releve_bancaire.centremonetique.service;

import com.example.releve_bancaire.centremonetique.dto.CentreMonetiqueExtractionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CentreMonetiqueExtractionService {

    private static final Pattern START_PATTERN = Pattern.compile(
            "^\\s*(?:(?:ACHAT|CREDIT\\s+VOUCHER)\\s+)?REM[I1L][S5]E(?:\\s+TPE)?(?:\\s+N[°\\*.]?[A-Z0-9-]+)?\\b.*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TOTAL_REMISE_PATTERN = Pattern.compile("\\bTOTAL\\s+REM[I1L][S5]E(?:S)?(?:\\s*\\(\\s*D?H\\s*\\))?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_COMMISSIONS_PATTERN = Pattern.compile("\\bTOTAL\\s+COMMISSIONS?(?:\\s+HT)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_TVA_PATTERN = Pattern.compile("\\bTOTAL\\s+TVA\\s+SUR\\s+COMMISSIONS?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOLDE_NET_PATTERN = Pattern.compile("\\bS[O0]LDE\\s+NET\\s+REM[I1L][S5]E\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[\\/\\-.]\\s*(\\d{1,2})(?:\\s*[\\/\\-.]\\s*(\\d{2,4}))?(?!\\d)");
    private static final Pattern TIME_PATTERN = Pattern.compile("\\b(\\d{1,2}:\\d{2})\\b");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\d{1,3}(?:[\\s.,]\\d{3})+|\\d+)(?:[.,]\\d{2})(?!\\d)");
    private static final Pattern INTEGER_TOKEN_PATTERN = Pattern.compile("\\b\\d{4,10}\\b");

    // Extraction du RIB depuis l'en-tête du document centre monétique.
    // CMI  : "RIB COMPTE DE DOMICILIATION : 022450000172000506932853"
    // Barid: "NCompte: 022450000172000506932853"
    private static final Pattern CMI_RIB_PATTERN = Pattern.compile(
            "\\bRIB(?:\\s+COMPTE(?:\\s+DE\\s+DOMICILIATION)?)?\\s*[:\\-]?\\s*([0-9]{20,30})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARID_RIB_NCOMPTE_PATTERN = Pattern.compile(
            "\\bN\\s*COMPTE\\s*[:\\-]?\\s*([0-9]{20,30})\\b",
            Pattern.CASE_INSENSITIVE);
    /** Fallback : toute séquence de 24 chiffres isolés dans le texte (doit commencer par 0 = RIB marocain). */
    private static final Pattern STANDALONE_24_DIGITS_PATTERN = Pattern.compile(
            "(?<!\\d)(0[0-9]{23})(?!\\d)");
    /**
     * Cherche un bloc de chiffres séparés par des espaces dont la concaténation donne exactement 24 digits.
     * Ex : "022 450 0001720005069328 53"  → "022450000172000506932853"
     */
    private static final Pattern RIB_SPACED_PATTERN = Pattern.compile(
            "(\\d[\\d ]{22,32}\\d)");

    private static final Pattern CARD_TYPE_PATTERN = Pattern.compile("(?:^|\\s)([VM])(?:\\s|$)");

    /**
     * Extrait le numéro TPE depuis la ligne d'en-tête d'un bloc REMISE CMI.
     * Ex: "ACHAT REMISE TPE N° :000285 DU :03/02/26 (DH)" → "000285"
     */
    private static final Pattern CMI_TPE_NUMBER_PATTERN = Pattern.compile(
            "N[^\\s0-9A-Z]{0,3}\\s*:?\\s*([0-9A-Z]{4,10})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BARID_REGLEMENT_PATTERN = Pattern.compile("^\\s*REGLEMENT\\s*[:\\-]?\\s*([A-Z0-9]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARID_DATE_REGLEMENT_PATTERN = Pattern.compile("\\bDATE\\s+REGLEMENT\\s*[:\\-]?\\s*(\\d{1,2}[\\/\\-.]\\d{1,2}[\\/\\-.]\\d{2,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARID_ACCOUNT_PATTERN = Pattern.compile("\\bN\\s*COMPTE\\s*[:\\-]?\\s*([0-9]{10,30})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARID_END_BLOCK_PATTERN = Pattern.compile("\\bMONTANT\\s+DE\\s+REGLEMENT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARID_MONTANT_REGLEMENT_CAPTURE = Pattern.compile("MONTANT\\s+DE\\s+REGLEMENT\\s*[:\\-]?\\s*([0-9.,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARID_MONTANT_REGLEMENT_DC_CAPTURE = Pattern.compile("MONTANT\\s+DE\\s+REGLEMENT\\s*[:\\-]?\\s*[0-9.,]+\\s*([CD])\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARID_COMM_HT_CAPTURE = Pattern.compile(
            "COMM\\s*\\.?\\s*H\\s*\\.?\\s*T(?:\\s*\\.(?!\\d))?\\s*[:\\-]?\\s*([0-9][0-9.,]*|[.,][0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARID_TVA_CAPTURE = Pattern.compile(
            "COMM\\s*\\.?\\s*TVA(?:\\s*\\.(?!\\d))?\\s*[:\\-]?\\s*([0-9][0-9.,]*|[.,][0-9]+)",
            Pattern.CASE_INSENSITIVE);

    // AMEX patterns
    private static final Pattern AMEX_SETTLEMENT_DATE_PATTERN = Pattern.compile(
            "(?i)Settlement\\s+Date\\s+(\\d{1,2}/\\d{1,2}/\\d{4})");
    private static final Pattern AMEX_SETTLEMENT_REF_PATTERN = Pattern.compile(
            "(?i)Settlement\\s+Reference\\s+Number\\s+(\\S+)");
    /** Matches terminal sub-group header: "...{terminal_id} - {terminal_name} Submission Amount..." */
    private static final Pattern AMEX_TERMINAL_LINE_PATTERN = Pattern.compile(
            "(\\d{7,12})\\s*-\\s*[A-Za-z][A-Za-z\\s]+?\\s+[Ss]ubmission\\s+[Aa]mount",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMEX_DISCOUNT_LABEL_PATTERN = Pattern.compile(
            "(?i)Discount\\s+Amount\\s+([0-9][0-9.,]*)");
    private static final Pattern AMEX_NET_LABEL_PATTERN = Pattern.compile(
            "(?i)^Net\\s+Amount\\s+([0-9][0-9.,]*)");
    private static final Pattern AMEX_TOTAL_TERMINAL_PATTERN = Pattern.compile(
            "(?i)Total\\s+For\\s+Terminal\\b");
    private static final Pattern AMEX_SUB_TOTAL_PATTERN = Pattern.compile(
            "(?i)^Sub\\s+Total\\b");
    private static final Pattern AMEX_TX_LINE_PATTERN = Pattern.compile(
            "^(\\d{1,2}/\\d{1,2}/\\d{4})\\s+(\\d{1,2}/\\d{1,2}/\\d{4})\\s+(.*)");

    private final CentreMonetiqueOcrService ocrService;

    public ExtractionPayload extract(byte[] fileData,
                                     String filename,
                                     Integer statementYear,
                                     CentreMonetiqueStructureType requestedStructure) throws Exception {
        String text = ocrService.extractText(fileData, filename);
        return extractFromTextWithSummary(text, statementYear, requestedStructure);
    }

    public List<CentreMonetiqueExtractionRow> extractFromText(String text,
                                                              Integer statementYear,
                                                              CentreMonetiqueStructureType requestedStructure) {
        return extractFromTextWithSummary(text, statementYear, requestedStructure).rows();
    }

    public ExtractionPayload extractFromTextWithSummary(String text,
                                                        Integer statementYear,
                                                        CentreMonetiqueStructureType requestedStructure) {
        if (text == null || text.isBlank()) {
            return new ExtractionPayload(
                    text,
                    List.of(),
                    new SummaryTotals(null, null, null, null),
                    CentreMonetiqueStructureType.CMI.name(),
                    null);
        }

        CentreMonetiqueStructureType effectiveStructure = resolveStructure(text, requestedStructure);
        // AMEX documents (SUBMISSION DETAILS) never contain Moroccan RIBs — skip extraction entirely
        // to avoid false positives from ARN numbers / approval codes that form 24-digit sequences.
        String extractedRib = effectiveStructure == CentreMonetiqueStructureType.AMEX
                ? null
                : extractRibFromText(text);
        ExtractionPayload base;
        if (effectiveStructure == CentreMonetiqueStructureType.AMEX) {
            base = extractAmex(text, statementYear);
        } else if (effectiveStructure == CentreMonetiqueStructureType.BARID_BANK) {
            base = extractBarid(text, statementYear);
        } else {
            base = extractCmi(text, statementYear);
        }
        // On enrichit le payload avec le RIB extrait (prioritaire sur l'extraction interne si différent).
        String finalRib = base.extractedRib() != null ? base.extractedRib() : extractedRib;
        return new ExtractionPayload(base.rawOcrText(), base.rows(), base.summaryTotals(), base.detectedStructure(), finalRib);
    }

    private ExtractionPayload extractCmi(String text, Integer statementYear) {
        List<CentreMonetiqueExtractionRow> rows = new ArrayList<>();
        String[] lines = text.split("\\n");
        boolean inRemiseSection = false;
        String currentTpe = null;
        BigDecimal blockTotalRemise = null;
        BigDecimal blockTotalCommissions = null;
        BigDecimal blockTotalTva = null;
        BigDecimal blockSoldeNetRemise = null;
        BigDecimal blockRemiseSum = BigDecimal.ZERO;
        BigDecimal sumTotalRemises = BigDecimal.ZERO;
        BigDecimal sumTotalCommissions = BigDecimal.ZERO;
        BigDecimal sumTotalTva = BigDecimal.ZERO;
        BigDecimal sumSoldeNetRemise = BigDecimal.ZERO;
        boolean hasTotalRemises = false;
        boolean hasTotalCommissions = false;
        boolean hasTotalTva = false;
        boolean hasSoldeNetRemise = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            String upper = normalizeUpper(line);

            boolean hasTotalMarker = false;
            if (TOTAL_REMISE_PATTERN.matcher(upper).find()) {
                blockTotalRemise = extractLastAmount(line);
                hasTotalMarker = true;
            }
            if (TOTAL_COMMISSIONS_PATTERN.matcher(upper).find()) {
                blockTotalCommissions = extractLastAmount(line);
                hasTotalMarker = true;
            }
            if (TOTAL_TVA_PATTERN.matcher(upper).find()) {
                blockTotalTva = extractLastAmount(line);
                hasTotalMarker = true;
            }
            if (SOLDE_NET_PATTERN.matcher(upper).find()) {
                blockSoldeNetRemise = extractLastAmount(line);
                hasTotalMarker = true;
            }
            if (hasTotalMarker && !START_PATTERN.matcher(upper).find()) {
                continue;
            }

            if (START_PATTERN.matcher(upper).find()) {
                if (inRemiseSection) {
                    BlockTotals blockTotals = resolveBlockTotals(
                            blockTotalRemise, blockTotalCommissions, blockTotalTva, blockSoldeNetRemise, blockRemiseSum);
                    appendBlockTotal(rows, blockTotals.totalRemise(), blockTotals.totalCommissions(),
                            blockTotals.totalTva(), blockTotals.soldeNetRemise());
                    if (blockTotals.totalRemise() != null) {
                        sumTotalRemises = sumTotalRemises.add(blockTotals.totalRemise());
                        hasTotalRemises = true;
                    }
                    if (blockTotals.totalCommissions() != null) {
                        sumTotalCommissions = sumTotalCommissions.add(blockTotals.totalCommissions());
                        hasTotalCommissions = true;
                    }
                    if (blockTotals.totalTva() != null) {
                        sumTotalTva = sumTotalTva.add(blockTotals.totalTva());
                        hasTotalTva = true;
                    }
                    if (blockTotals.soldeNetRemise() != null) {
                        sumSoldeNetRemise = sumSoldeNetRemise.add(blockTotals.soldeNetRemise());
                        hasSoldeNetRemise = true;
                    }
                }
                inRemiseSection = true;
                // Extraire le numéro TPE du terminal depuis la ligne d'en-tête
                Matcher tpeMatcher = CMI_TPE_NUMBER_PATTERN.matcher(line);
                currentTpe = tpeMatcher.find() ? tpeMatcher.group(1) : null;
                // Stocker une ligne d'en-tête REMISE ACHAT avec le numéro TPE
                if (currentTpe != null && !currentTpe.isBlank()) {
                    rows.add(new CentreMonetiqueExtractionRow(
                            "REMISE ACHAT", "", currentTpe, "", "", "", ""));
                }
                blockTotalRemise = null;
                blockTotalCommissions = null;
                blockTotalTva = null;
                blockSoldeNetRemise = null;
                blockRemiseSum = BigDecimal.ZERO;
                continue;
            }

            if (!inRemiseSection) {
                continue;
            }

            if (isHeaderLine(upper)) {
                continue;
            }

            CentreMonetiqueExtractionRow transaction = parseCmiTransactionLine(line, statementYear);
            if (transaction != null) {
                // Tagger la section avec le numéro TPE pour le rapprochement
                if (currentTpe != null && !currentTpe.isBlank()) {
                    transaction = new CentreMonetiqueExtractionRow(
                            "REMISE " + currentTpe,
                            transaction.getDate(),
                            transaction.getReference(),
                            transaction.getMontant(),
                            transaction.getDebit(),
                            transaction.getCredit(),
                            transaction.getDc());
                }
                rows.add(transaction);
                BigDecimal txAmount = parseAmount(transaction.getMontant());
                if (txAmount != null) {
                    blockRemiseSum = blockRemiseSum.add(txAmount);
                }
            }
        }

        if (inRemiseSection) {
            BlockTotals blockTotals = resolveBlockTotals(
                    blockTotalRemise, blockTotalCommissions, blockTotalTva, blockSoldeNetRemise, blockRemiseSum);
            appendBlockTotal(rows, blockTotals.totalRemise(), blockTotals.totalCommissions(),
                    blockTotals.totalTva(), blockTotals.soldeNetRemise());
            if (blockTotals.totalRemise() != null) {
                sumTotalRemises = sumTotalRemises.add(blockTotals.totalRemise());
                hasTotalRemises = true;
            }
            if (blockTotals.totalCommissions() != null) {
                sumTotalCommissions = sumTotalCommissions.add(blockTotals.totalCommissions());
                hasTotalCommissions = true;
            }
            if (blockTotals.totalTva() != null) {
                sumTotalTva = sumTotalTva.add(blockTotals.totalTva());
                hasTotalTva = true;
            }
            if (blockTotals.soldeNetRemise() != null) {
                sumSoldeNetRemise = sumSoldeNetRemise.add(blockTotals.soldeNetRemise());
                hasSoldeNetRemise = true;
            }
        }

        SummaryTotals totals = new SummaryTotals(
                hasTotalRemises ? scale2(sumTotalRemises) : null,
                hasTotalCommissions ? scale2(sumTotalCommissions) : null,
                hasTotalTva ? scale2(sumTotalTva) : null,
                hasSoldeNetRemise ? scale2(sumSoldeNetRemise) : null);

        return new ExtractionPayload(text, rows, totals, CentreMonetiqueStructureType.CMI.name());
    }

    private ExtractionPayload extractBarid(String text, Integer statementYear) {
        List<CentreMonetiqueExtractionRow> rows = new ArrayList<>();
        String[] lines = text.split("\\n");

        String currentReglement = null;
        String currentDateReglement = "";
        String currentCompte = "";
        BigDecimal blockMontantReglement = null;
        String blockMontantReglementDc = null;
        BigDecimal blockCommissionHt = null;
        BigDecimal blockTva = null;
        BigDecimal txMontantSum = BigDecimal.ZERO;
        BigDecimal txCommissionSum = BigDecimal.ZERO;

        BigDecimal sumTotalRemises = BigDecimal.ZERO;
        BigDecimal sumTotalCommissions = BigDecimal.ZERO;
        BigDecimal sumTotalTva = BigDecimal.ZERO;
        BigDecimal sumSoldeNetRemise = BigDecimal.ZERO;
        boolean hasTotalRemises = false;
        boolean hasTotalCommissions = false;
        boolean hasTotalTva = false;
        boolean hasSoldeNetRemise = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String upper = normalizeUpper(line);

            Matcher regMatcher = BARID_REGLEMENT_PATTERN.matcher(upper);
            if (regMatcher.find()) {
                if (currentReglement != null) {
                    BlockTotals totals = resolveBlockTotals(
                            blockMontantReglement,
                            blockCommissionHt,
                            blockTva,
                            null,
                            txMontantSum);
                    appendBlockTotal(rows, totals.totalRemise(), totals.totalCommissions(), totals.totalTva(), totals.soldeNetRemise());
                    if (totals.totalRemise() != null) {
                        sumTotalRemises = sumTotalRemises.add(totals.totalRemise());
                        hasTotalRemises = true;
                    }
                    if (totals.totalCommissions() != null) {
                        sumTotalCommissions = sumTotalCommissions.add(totals.totalCommissions());
                        hasTotalCommissions = true;
                    }
                    if (totals.totalTva() != null) {
                        sumTotalTva = sumTotalTva.add(totals.totalTva());
                        hasTotalTva = true;
                    }
                    if (totals.soldeNetRemise() != null) {
                        sumSoldeNetRemise = sumSoldeNetRemise.add(totals.soldeNetRemise());
                        hasSoldeNetRemise = true;
                    }
                }

                currentReglement = regMatcher.group(1);
                currentDateReglement = "";
                currentCompte = "";
                blockMontantReglement = null;
                blockMontantReglementDc = null;
                blockCommissionHt = null;
                blockTva = null;
                txMontantSum = BigDecimal.ZERO;
                txCommissionSum = BigDecimal.ZERO;
                continue;
            }

            if (currentReglement == null) {
                continue;
            }

            Matcher dateRegMatcher = BARID_DATE_REGLEMENT_PATTERN.matcher(upper);
            if (dateRegMatcher.find()) {
                String normalized = normalizeDateToken(dateRegMatcher.group(1), statementYear);
                if (normalized != null) {
                    currentDateReglement = normalized;
                }
                continue;
            }

            Matcher accountMatcher = BARID_ACCOUNT_PATTERN.matcher(upper);
            if (accountMatcher.find()) {
                currentCompte = accountMatcher.group(1);
                continue;
            }

            BaridTransaction tx = parseBaridTransactionLine(line, statementYear);
            if (tx != null) {
                String txDate = tx.date();
                String card = tx.cardNumber();
                String libelle = tx.libelle();
                BigDecimal montant = tx.montant();
                String dc = tx.dc();
                BigDecimal commission = tx.commissionHt();
                String systeme = tx.systeme();

                rows.add(new CentreMonetiqueExtractionRow(
                        "REGLEMENT " + currentReglement,
                        txDate != null ? txDate : "",
                        card,
                        formatAmount(montant),
                        formatAmount(commission),
                        dc + " | " + systeme + " | " + libelle,
                        dc));

                if (montant != null) {
                    txMontantSum = txMontantSum.add(montant);
                }
                if (commission != null) {
                    txCommissionSum = txCommissionSum.add(commission);
                }
                continue;
            }

            if (BARID_END_BLOCK_PATTERN.matcher(upper).find()) {
                blockMontantReglement = extractCapturedAmount(line, BARID_MONTANT_REGLEMENT_CAPTURE);
                blockMontantReglementDc = extractCapturedToken(line, BARID_MONTANT_REGLEMENT_DC_CAPTURE);
                blockCommissionHt = extractCapturedAmount(line, BARID_COMM_HT_CAPTURE);
                blockTva = extractCapturedAmount(line, BARID_TVA_CAPTURE);

                BlockTotals totals = resolveBlockTotals(
                        blockMontantReglement,
                        blockCommissionHt,
                        blockTva,
                        null,
                        txMontantSum);

                rows.add(new CentreMonetiqueExtractionRow(
                        "REGLEMENT META",
                        currentDateReglement,
                        currentCompte,
                        "",
                        "",
                        "REGLEMENT " + currentReglement,
                        ""));
                rows.add(new CentreMonetiqueExtractionRow(
                        "REGLEMENT TOTALS",
                        "",
                        currentReglement,
                        formatAmount(blockMontantReglement),
                        formatAmount4(blockCommissionHt),
                        formatAmount4(blockTva),
                        blockMontantReglementDc != null ? blockMontantReglementDc : ""));
                appendBlockTotal(rows, totals.totalRemise(), totals.totalCommissions(), totals.totalTva(), totals.soldeNetRemise());

                if (totals.totalRemise() != null) {
                    sumTotalRemises = sumTotalRemises.add(totals.totalRemise());
                    hasTotalRemises = true;
                }
                if (totals.totalCommissions() != null) {
                    sumTotalCommissions = sumTotalCommissions.add(totals.totalCommissions());
                    hasTotalCommissions = true;
                }
                if (totals.totalTva() != null) {
                    sumTotalTva = sumTotalTva.add(totals.totalTva());
                    hasTotalTva = true;
                }
                if (totals.soldeNetRemise() != null) {
                    sumSoldeNetRemise = sumSoldeNetRemise.add(totals.soldeNetRemise());
                    hasSoldeNetRemise = true;
                }

                currentReglement = null;
                currentDateReglement = "";
                currentCompte = "";
                blockMontantReglement = null;
                blockMontantReglementDc = null;
                blockCommissionHt = null;
                blockTva = null;
                txMontantSum = BigDecimal.ZERO;
                txCommissionSum = BigDecimal.ZERO;
            }
        }

        if (currentReglement != null) {
            BlockTotals totals = resolveBlockTotals(
                    blockMontantReglement, blockCommissionHt, blockTva, null, txMontantSum);
            rows.add(new CentreMonetiqueExtractionRow(
                    "REGLEMENT META",
                    currentDateReglement,
                    currentCompte,
                    "",
                    "",
                    "REGLEMENT " + currentReglement,
                    ""));
            rows.add(new CentreMonetiqueExtractionRow(
                    "REGLEMENT TOTALS",
                    "",
                    currentReglement,
                    formatAmount(blockMontantReglement),
                    formatAmount4(blockCommissionHt),
                    formatAmount4(blockTva),
                    blockMontantReglementDc != null ? blockMontantReglementDc : ""));
            appendBlockTotal(rows, totals.totalRemise(), totals.totalCommissions(), totals.totalTva(), totals.soldeNetRemise());
            if (totals.totalRemise() != null) {
                sumTotalRemises = sumTotalRemises.add(totals.totalRemise());
                hasTotalRemises = true;
            }
            if (totals.totalCommissions() != null) {
                sumTotalCommissions = sumTotalCommissions.add(totals.totalCommissions());
                hasTotalCommissions = true;
            }
            if (totals.totalTva() != null) {
                sumTotalTva = sumTotalTva.add(totals.totalTva());
                hasTotalTva = true;
            }
            if (totals.soldeNetRemise() != null) {
                sumSoldeNetRemise = sumSoldeNetRemise.add(totals.soldeNetRemise());
                hasSoldeNetRemise = true;
            }
        }

        SummaryTotals totals = new SummaryTotals(
                hasTotalRemises ? scale2(sumTotalRemises) : null,
                hasTotalCommissions ? scale2(sumTotalCommissions) : null,
                hasTotalTva ? scale2(sumTotalTva) : null,
                hasSoldeNetRemise ? scale2(sumSoldeNetRemise) : null);

        return new ExtractionPayload(text, rows, totals, CentreMonetiqueStructureType.BARID_BANK.name());
    }

    /**
     * Extraction AMEX "Submission Details".
     * Sections produites :
     *   "AMEX SETTLEMENT"     – en-tête de règlement (date, ref)
     *   "AMEX TERMINAL"       – en-tête de terminal (terminal_id)
     *   "AMEX {terminal_id}"  – ligne de transaction individuelle
     *   "AMEX TOTAL TERMINAL" – total par terminal
     *   "AMEX SUB TOTAL"      – sous-total du règlement
     */
    private ExtractionPayload extractAmex(String text, Integer statementYear) {
        List<CentreMonetiqueExtractionRow> rows = new ArrayList<>();
        String[] lines = text.split("\\n");

        String settlementDate = "";
        String settlementRef = "";
        String currentTerminalId = "";
        boolean inDocument = false;
        boolean awaitingTerminalDiscount = false;
        boolean awaitingTerminalNet = false;
        BigDecimal totalSubmission = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        boolean hasTransactions = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) continue;
            String upper = normalizeUpper(line);

            // Settlement header: "... Settlement Date DD/MM/YYYY"
            Matcher settleDateMatcher = AMEX_SETTLEMENT_DATE_PATTERN.matcher(line);
            if (settleDateMatcher.find()) {
                settlementDate = settleDateMatcher.group(1);
                inDocument = true;
                awaitingTerminalDiscount = false;
                awaitingTerminalNet = false;
                Matcher refMatcher = AMEX_SETTLEMENT_REF_PATTERN.matcher(line);
                if (refMatcher.find()) {
                    settlementRef = refMatcher.group(1);
                }
                rows.add(new CentreMonetiqueExtractionRow(
                        "AMEX SETTLEMENT", settlementDate, settlementRef, "", "", "", ""));
                continue;
            }

            if (!inDocument) continue;

            // Settlement reference number (separate line)
            if (settlementRef.isEmpty()) {
                Matcher refMatcher = AMEX_SETTLEMENT_REF_PATTERN.matcher(line);
                if (refMatcher.find()) {
                    settlementRef = refMatcher.group(1);
                    continue;
                }
            }

            // Skip column header line
            if (upper.contains("SUBMISSION DATE") && upper.contains("TRANSACTION DATE")) {
                continue;
            }

            // Terminal sub-group header: "... {terminal_id} - {terminal_name} Submission Amount ..."
            Matcher terminalLineMatcher = AMEX_TERMINAL_LINE_PATTERN.matcher(line);
            if (terminalLineMatcher.find()) {
                currentTerminalId = terminalLineMatcher.group(1);
                awaitingTerminalDiscount = true;
                awaitingTerminalNet = false;
                rows.add(new CentreMonetiqueExtractionRow(
                        "AMEX TERMINAL", settlementDate, currentTerminalId, "", "", "", ""));
                continue;
            }

            // Discount Amount line following terminal header
            if (awaitingTerminalDiscount) {
                Matcher discountMatcher = AMEX_DISCOUNT_LABEL_PATTERN.matcher(line);
                if (discountMatcher.find()) {
                    awaitingTerminalDiscount = false;
                    awaitingTerminalNet = true;
                    continue;
                }
            }

            // Net Amount line following discount line
            if (awaitingTerminalNet) {
                Matcher netMatcher = AMEX_NET_LABEL_PATTERN.matcher(line);
                if (netMatcher.find()) {
                    awaitingTerminalNet = false;
                    continue;
                }
            }

            // Transaction line: starts with DD/MM/YYYY DD/MM/YYYY
            Matcher txMatcher = AMEX_TX_LINE_PATTERN.matcher(line);
            if (txMatcher.find()) {
                AmexTransaction tx = parseAmexTransactionLine(line);
                if (tx != null) {
                    String sectionName = "AMEX " + (currentTerminalId.isEmpty() ? "TX" : currentTerminalId);
                    rows.add(new CentreMonetiqueExtractionRow(
                            sectionName,
                            tx.submissionDate(),
                            tx.cardNumber(),
                            formatAmount(tx.submission()),
                            formatAmount(tx.discount()),
                            formatAmount(tx.net()),
                            tx.transactionDate()));
                    if (tx.submission() != null) totalSubmission = totalSubmission.add(tx.submission());
                    if (tx.discount() != null) totalDiscount = totalDiscount.add(tx.discount());
                    if (tx.net() != null) totalNet = totalNet.add(tx.net());
                    hasTransactions = true;
                }
                continue;
            }

            // Total For Terminal line
            if (AMEX_TOTAL_TERMINAL_PATTERN.matcher(upper).find()) {
                List<BigDecimal> amounts = extractAllAmounts(line);
                BigDecimal totSub  = amounts.size() >= 3 ? amounts.get(amounts.size() - 3) : null;
                BigDecimal totDisc = amounts.size() >= 2 ? amounts.get(amounts.size() - 2) : null;
                BigDecimal totNet  = amounts.size() >= 1 ? amounts.get(amounts.size() - 1) : null;
                rows.add(new CentreMonetiqueExtractionRow(
                        "AMEX TOTAL TERMINAL", "",
                        currentTerminalId,
                        formatAmount(totSub), formatAmount(totDisc), formatAmount(totNet), ""));
                continue;
            }

            // Sub Total line (settlement level)
            if (AMEX_SUB_TOTAL_PATTERN.matcher(upper).find()) {
                List<BigDecimal> amounts = extractAllAmounts(line);
                BigDecimal totSub  = amounts.size() >= 3 ? amounts.get(amounts.size() - 3) : null;
                BigDecimal totDisc = amounts.size() >= 2 ? amounts.get(amounts.size() - 2) : null;
                BigDecimal totNet  = amounts.size() >= 1 ? amounts.get(amounts.size() - 1) : null;
                rows.add(new CentreMonetiqueExtractionRow(
                        "AMEX SUB TOTAL",
                        settlementDate,
                        settlementRef,
                        formatAmount(totSub != null ? totSub : totalSubmission),
                        formatAmount(totDisc != null ? totDisc : totalDiscount),
                        formatAmount(totNet != null ? totNet : totalNet),
                        ""));
            }
        }

        SummaryTotals totals = new SummaryTotals(
                hasTransactions ? scale2(totalSubmission) : null,
                hasTransactions ? scale2(totalDiscount) : null,
                null,
                hasTransactions ? scale2(totalNet) : null);

        return new ExtractionPayload(text, rows, totals, CentreMonetiqueStructureType.AMEX.name());
    }

    /**
     * Parse une ligne de transaction AMEX.
     * Format : DD/MM/YYYY DD/MM/YYYY {card} {terminal} {batch} {rate} {submission} {discount} {net} {approval} {arn}
     * Les 3 derniers montants décimaux (X.XX) sont submission, discount, net.
     */
    private AmexTransaction parseAmexTransactionLine(String line) {
        String trimmed = line.trim().replaceAll("\\s+", " ");
        String[] tokens = trimmed.split(" ");
        if (tokens.length < 8) return null;
        if (!tokens[0].matches("\\d{1,2}/\\d{1,2}/\\d{4}")) return null;
        if (!tokens[1].matches("\\d{1,2}/\\d{1,2}/\\d{4}")) return null;

        String submissionDate   = tokens[0]; // first date DD/MM/YYYY
        String transactionDate  = tokens[1]; // second date DD/MM/YYYY

        // Card number: first token after the two dates that contains '-'
        String cardNumber = "";
        for (int i = 2; i < Math.min(tokens.length, 7); i++) {
            if (tokens[i].contains("-") && tokens[i].matches("[0-9*Xx\\-]{10,25}")) {
                cardNumber = tokens[i];
                break;
            }
        }

        // Collect decimal amounts in X.XX or X,XXX.XX format
        List<BigDecimal> amounts = new ArrayList<>();
        for (String token : tokens) {
            if (token.matches("\\d+\\.\\d{2}") || token.matches("\\d{1,3}(?:,\\d{3})*\\.\\d{2}")) {
                BigDecimal amount = parseAmount(token);
                if (amount != null) amounts.add(amount);
            }
        }
        if (amounts.size() < 3) return null;

        BigDecimal submission = amounts.get(amounts.size() - 3);
        BigDecimal discount   = amounts.get(amounts.size() - 2);
        BigDecimal net        = amounts.get(amounts.size() - 1);
        return new AmexTransaction(submissionDate, transactionDate, cardNumber, submission, discount, net);
    }

    private List<BigDecimal> extractAllAmounts(String line) {
        List<BigDecimal> amounts = new ArrayList<>();
        Matcher matcher = AMOUNT_PATTERN.matcher(line);
        while (matcher.find()) {
            BigDecimal parsed = parseAmount(matcher.group());
            if (parsed != null) amounts.add(parsed);
        }
        return amounts;
    }

    private CentreMonetiqueStructureType resolveStructure(String text, CentreMonetiqueStructureType requestedStructure) {
        CentreMonetiqueStructureType requested = requestedStructure != null ? requestedStructure : CentreMonetiqueStructureType.AUTO;
        if (requested != CentreMonetiqueStructureType.AUTO) {
            return requested;
        }
        String normalized = normalizeUpper(text);
        if (normalized.contains("SUBMISSION DETAILS")
                && (normalized.contains("SETTLEMENT DATE") || normalized.contains("DISCOUNT RATE"))) {
            return CentreMonetiqueStructureType.AMEX;
        }
        if (normalized.contains("REGLEMENT")
                && normalized.contains("MONTANT DE REGLEMENT")
                && normalized.contains("NCOMPTE")) {
            return CentreMonetiqueStructureType.BARID_BANK;
        }
        return CentreMonetiqueStructureType.CMI;
    }

    private void appendBlockTotal(List<CentreMonetiqueExtractionRow> rows,
                                  BigDecimal totalRemise,
                                  BigDecimal totalCommissions,
                                  BigDecimal totalTva,
                                  BigDecimal soldeNetRemise) {
        if (totalRemise == null && totalCommissions == null && totalTva == null && soldeNetRemise == null) {
            return;
        }
        if (totalRemise != null) {
            rows.add(new CentreMonetiqueExtractionRow(
                    "TOTAL REMISE (DH)",
                    "",
                    "",
                    formatAmount(totalRemise),
                    "",
                    "",
                    ""));
        }
        if (totalCommissions != null) {
            rows.add(new CentreMonetiqueExtractionRow(
                    "TOTAL COMMISSIONS HT",
                    "",
                    "",
                    "",
                    formatAmount(totalCommissions),
                    "",
                    ""));
        }
        if (totalTva != null) {
            rows.add(new CentreMonetiqueExtractionRow(
                    "TOTAL TVA SUR COMMISSIONS",
                    "",
                    "",
                    "",
                    formatAmount(totalTva),
                    "",
                    ""));
        }
        if (soldeNetRemise != null) {
            rows.add(new CentreMonetiqueExtractionRow(
                    "SOLDE NET REMISE",
                    "",
                    "",
                    "",
                    "",
                    formatAmount(soldeNetRemise),
                    ""));
        }
    }

    private BlockTotals resolveBlockTotals(BigDecimal totalRemise,
                                           BigDecimal totalCommissions,
                                           BigDecimal totalTva,
                                           BigDecimal soldeNetRemise,
                                           BigDecimal blockRemiseSum) {
        BigDecimal normalizedRemise = totalRemise;
        if (normalizedRemise == null
                && blockRemiseSum != null
                && blockRemiseSum.compareTo(BigDecimal.ZERO) > 0) {
            normalizedRemise = scale2(blockRemiseSum);
        }
        BigDecimal normalizedSolde = soldeNetRemise;
        return new BlockTotals(
                scale2(normalizedRemise),
                scale2(totalCommissions),
                scale2(totalTva),
                scale2(normalizedSolde));
    }

    private CentreMonetiqueExtractionRow parseCmiTransactionLine(String line, Integer statementYear) {
        Matcher dateMatcher = DATE_PATTERN.matcher(line);
        if (!dateMatcher.find()) {
            return null;
        }

        String formattedDate = normalizeDate(
                dateMatcher.group(1),
                dateMatcher.group(2),
                dateMatcher.group(3),
                statementYear);
        if (formattedDate == null) {
            return null;
        }

        String tail = line.substring(Math.min(dateMatcher.end(), line.length()));
        Matcher timeMatcher = TIME_PATTERN.matcher(tail);
        String time = timeMatcher.find() ? timeMatcher.group(1) : null;
        String reference = extractReference(tail);
        if (reference == null || reference.isBlank()) {
            return null;
        }

        BigDecimal montant = extractLastAmount(line);
        if (montant == null || montant.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // Extract card type V or M after the STAN reference
        String afterStan = tail;
        int stanIdx = reference != null ? tail.indexOf(reference) : -1;
        if (stanIdx >= 0) afterStan = tail.substring(stanIdx + reference.length());
        Matcher cardTypeMatcher = CARD_TYPE_PATTERN.matcher(afterStan);
        String cardType = cardTypeMatcher.find() ? cardTypeMatcher.group(1) : "";

        return new CentreMonetiqueExtractionRow(
                "Remise",
                time != null && !time.isBlank() ? (formattedDate + " " + time) : formattedDate,
                reference,
                formatAmount(montant),
                "",
                "",
                cardType);
    }

    private String extractReference(String lineTail) {
        Matcher matcher = INTEGER_TOKEN_PATTERN.matcher(lineTail == null ? "" : lineTail);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 4) {
                return token;
            }
        }
        return null;
    }

    private boolean isHeaderLine(String upper) {
        return (upper.contains("DATE") && upper.contains("STAN"))
                || upper.contains("TYPE DE CARTE")
                || upper.contains("NUMERO DE CARTE")
                || upper.contains("MONTANT TRANSACTION")
                || upper.contains("L/T")
                || upper.contains("DEBIT NET")
                || upper.contains("CREDIT NET");
    }

    private String normalizeDate(String dayToken, String monthToken, String yearToken, Integer statementYear) {
        try {
            int day = Integer.parseInt(dayToken);
            int month = Integer.parseInt(monthToken);
            if (day < 1 || day > 31 || month < 1 || month > 12) {
                return null;
            }

            int year = resolveYear(yearToken, statementYear);
            LocalDate date = LocalDate.of(year, month, day);
            return String.format("%02d/%02d/%02d", date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeDateToken(String rawDate, Integer statementYear) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        Matcher matcher = DATE_PATTERN.matcher(rawDate);
        if (!matcher.find()) {
            return null;
        }
        return normalizeDate(matcher.group(1), matcher.group(2), matcher.group(3), statementYear);
    }

    private String normalizeDateTimeToken(String rawDateTime, Integer statementYear) {
        if (rawDateTime == null || rawDateTime.isBlank()) {
            return "";
        }
        String trimmed = rawDateTime.trim();
        int idx = trimmed.indexOf(' ');
        String datePart = idx > 0 ? trimmed.substring(0, idx) : trimmed;
        String timePart = idx > 0 ? trimmed.substring(idx + 1).trim() : "";
        String normalizedDate = normalizeDateToken(datePart, statementYear);
        if (normalizedDate == null) {
            return trimmed;
        }
        if (timePart.isBlank()) {
            return normalizedDate;
        }
        return normalizedDate + " " + timePart;
    }

    private BaridTransaction parseBaridTransactionLine(String line, Integer statementYear) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim().replaceAll("\\s+", " ");
        String[] tokens = trimmed.split(" ");
        if (tokens.length < 8) {
            return null;
        }
        if (!tokens[0].matches("\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}")) {
            return null;
        }
        if (!tokens[1].matches("\\d{1,2}:\\d{2}")) {
            return null;
        }

        int cardIndex = -1;
        for (int i = 2; i < tokens.length; i++) {
            if (tokens[i].contains("*") && tokens[i].matches("[0-9*Xx]{10,30}")) {
                cardIndex = i;
                break;
            }
        }
        if (cardIndex < 0) {
            return null;
        }

        int amountIndex = -1;
        for (int i = cardIndex + 1; i < tokens.length; i++) {
            if (isAmountToken(tokens[i])) {
                amountIndex = i;
                break;
            }
        }
        if (amountIndex < 0 || amountIndex + 2 >= tokens.length) {
            return null;
        }

        String dc = tokens[amountIndex + 1].toUpperCase(Locale.ROOT);
        if (!"C".equals(dc) && !"D".equals(dc)) {
            return null;
        }
        if (!isAmountToken(tokens[amountIndex + 2])) {
            return null;
        }

        StringBuilder libelleBuilder = new StringBuilder();
        for (int i = cardIndex + 1; i < amountIndex; i++) {
            if (!libelleBuilder.isEmpty()) {
                libelleBuilder.append(' ');
            }
            libelleBuilder.append(tokens[i]);
        }

        StringBuilder systemeBuilder = new StringBuilder();
        for (int i = amountIndex + 3; i < tokens.length; i++) {
            if (!systemeBuilder.isEmpty()) {
                systemeBuilder.append(' ');
            }
            systemeBuilder.append(tokens[i]);
        }

        String date = normalizeDateTimeToken(tokens[0] + " " + tokens[1], statementYear);
        String card = tokens[cardIndex];
        BigDecimal montant = parseAmount(tokens[amountIndex]);
        BigDecimal commission = parseAmount(tokens[amountIndex + 2]);
        String libelle = libelleBuilder.toString().trim();
        String systeme = systemeBuilder.toString().trim().toUpperCase(Locale.ROOT);

        if (date == null || date.isBlank() || montant == null || commission == null) {
            return null;
        }

        return new BaridTransaction(date, card, libelle, montant, dc, commission, systeme);
    }

    private boolean isAmountToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return token.matches("(?:\\d{1,3}(?:[\\s.,]\\d{3})+|\\d+)(?:[.,]\\d{2,3})?");
    }

    private int resolveYear(String yearToken, Integer statementYear) {
        if (yearToken != null && !yearToken.isBlank()) {
            int parsed = Integer.parseInt(yearToken);
            if (parsed >= 100) {
                return parsed;
            }
            return parsed <= 79 ? 2000 + parsed : 1900 + parsed;
        }
        if (statementYear != null && statementYear >= 1900 && statementYear <= 2100) {
            return statementYear;
        }
        return LocalDate.now().getYear();
    }

    private BigDecimal extractLastAmount(String line) {
        Matcher matcher = AMOUNT_PATTERN.matcher(line);
        BigDecimal amount = null;
        while (matcher.find()) {
            BigDecimal parsed = parseAmount(matcher.group());
            if (parsed != null) {
                amount = parsed;
            }
        }
        return amount;
    }

    private BigDecimal extractCapturedAmount(String line, Pattern pattern) {
        if (line == null || pattern == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        return parseAmount(matcher.group(1));
    }

    private String extractCapturedToken(String line, Pattern pattern) {
        if (line == null || pattern == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).toUpperCase(Locale.ROOT);
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replaceAll("\\s+", "").trim();
        boolean negative = normalized.startsWith("-") || normalized.contains("-")
                || normalized.startsWith("(") || normalized.endsWith(")");
        normalized = normalized.replace("(", "").replace(")", "").replace("-", "");
        try {
            int lastDot = normalized.lastIndexOf('.');
            int lastComma = normalized.lastIndexOf(',');

            if (lastDot >= 0 || lastComma >= 0) {
                int decimalIndex = Math.max(lastDot, lastComma);
                char decimalSep = normalized.charAt(decimalIndex);
                String integerPart = normalized.substring(0, decimalIndex).replace(",", "").replace(".", "");
                String decimalPart = normalized.substring(decimalIndex + 1).replace(",", "").replace(".", "");
                if (integerPart.isBlank()) {
                    integerPart = "0";
                }
                String canonical = integerPart + (decimalPart.isBlank() ? "" : "." + decimalPart);
                BigDecimal value = new BigDecimal(canonical);
                return negative ? value.abs().negate() : value.abs();
            }

            String digitsOnly = normalized.replaceAll("[^0-9]", "");
            if (digitsOnly.isEmpty()) {
                return null;
            }
            BigDecimal value = new BigDecimal(digitsOnly);
            return negative ? value.abs().negate() : value.abs();
        } catch (Exception e) {
            String digitsOnly = normalized.replaceAll("[^0-9]", "");
            if (digitsOnly.isEmpty()) {
                return null;
            }
            try {
                BigDecimal fallback = new BigDecimal(digitsOnly);
                return fallback.movePointLeft(2).abs();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private BigDecimal scale2(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String formatAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0, RoundingMode.HALF_UP);
        }
        return normalized.toPlainString();
    }

    private String formatAmount4(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private String normalizeUpper(String line) {
        return java.text.Normalizer.normalize(line, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Extrait le RIB (24 chiffres) depuis le texte OCR du centre monétique.
     * Priorité :
     *   1) CMI compact   : "RIB COMPTE DE DOMICILIATION : 022450000172000506932853"
     *   2) Saham/split   : "RIB COMPTE DE DOMICILIATION :\n022 450 0001720005069328 53"
     *   3) Barid NCompte : "NCompte: 022450000172000506932853"
     *   4) Fallback      : première séquence de 24 chiffres isolés
     */
    public String extractRibFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 1) CMI compact : RIB contigu sur la même ligne que le label
        Matcher m1 = CMI_RIB_PATTERN.matcher(text);
        if (m1.find()) {
            return m1.group(1);
        }
        // 2) RIB label trouvé : cherche 24 chiffres dans le voisinage (même ligne + 3 suivantes)
        //    Gère : chiffres groupés avec espaces, chiffres sur la ligne suivante, lignes mixtes.
        String[] lines = text.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String upper = normalizeUpper(lines[i]);
            boolean hasLabel = upper.contains("RIB COMPTE DE DOMICILIATION")
                    || upper.contains("RIB COMPTE")
                    || upper.contains("COMPTE DE DOMICILIATION");
            if (!hasLabel) continue;

            // Fenêtre de recherche : ligne actuelle + 3 suivantes
            for (int j = i; j <= Math.min(i + 3, lines.length - 1); j++) {
                String candidate = lines[j];
                // a) chiffres contigus == 24
                String stripped = candidate.replaceAll("[^0-9]", "");
                if (stripped.length() == 24) {
                    return stripped;
                }
                // b) cherche un bloc "chiffres + espaces" dont les digits font 24
                Matcher sm = RIB_SPACED_PATTERN.matcher(candidate);
                while (sm.find()) {
                    String digits = sm.group(1).replaceAll("[^0-9]", "");
                    if (digits.length() == 24) {
                        return digits;
                    }
                }
                // c) si digits > 24, prendre les 24 premiers (cas ligne label + RIB contigus)
                if (stripped.length() > 24 && stripped.length() <= 30) {
                    // Vérifier qu'il n'y a pas d'autres chiffres parasites en cherchant le RIB
                    // comme le plus long bloc contigu de chiffres de la ligne
                    Matcher dm = Pattern.compile("[0-9]{20,30}").matcher(candidate);
                    while (dm.find()) {
                        String g = dm.group();
                        if (g.length() == 24) return g;
                        if (g.length() > 24) return g.substring(0, 24);
                    }
                }
            }
            // d) concaténation de plusieurs lignes successives
            for (int k = 1; k <= 3 && (i + k) < lines.length; k++) {
                String combined = String.join("", java.util.Arrays.copyOfRange(lines, i, i + k + 1))
                        .replaceAll("[^0-9]", "");
                if (combined.length() == 24) {
                    return combined;
                }
            }
        }
        // 3) Barid Bank : "NCompte: 022450000172000506932853"
        Matcher m2 = BARID_RIB_NCOMPTE_PATTERN.matcher(text);
        if (m2.find()) {
            return m2.group(1);
        }
        // 4) Fallback : première séquence de 24 chiffres isolés
        Matcher m3 = STANDALONE_24_DIGITS_PATTERN.matcher(text);
        if (m3.find()) {
            return m3.group(1);
        }
        // 5) Fallback global pour RIB espacés (ex: "022 450 0001720005069328 53")
        //    Cherche dans chaque ligne un bloc chiffres+espaces dont la concaténation fait 24 digits
        //    Et qui commence par 0 (RIB marocain valide)
        for (String line : text.split("\\n")) {
            Matcher sm = RIB_SPACED_PATTERN.matcher(line.trim());
            while (sm.find()) {
                String digits = sm.group(1).replaceAll("[^0-9]", "");
                if (digits.length() == 24 && digits.startsWith("0")) {
                    return digits;
                }
            }
        }
        return null;
    }

    public record ExtractionPayload(String rawOcrText,
                                    List<CentreMonetiqueExtractionRow> rows,
                                    SummaryTotals summaryTotals,
                                    String detectedStructure,
                                    String extractedRib) {

        /** Constructeur de compatibilité sans RIB. */
        public ExtractionPayload(String rawOcrText, List<CentreMonetiqueExtractionRow> rows,
                                 SummaryTotals summaryTotals, String detectedStructure) {
            this(rawOcrText, rows, summaryTotals, detectedStructure, null);
        }
    }

    private record BlockTotals(BigDecimal totalRemise,
                               BigDecimal totalCommissions,
                               BigDecimal totalTva,
                               BigDecimal soldeNetRemise) {
    }

    private record BaridTransaction(String date,
                                    String cardNumber,
                                    String libelle,
                                    BigDecimal montant,
                                    String dc,
                                    BigDecimal commissionHt,
                                    String systeme) {
    }

    public record SummaryTotals(BigDecimal totalRemises,
                                BigDecimal totalCommissionsHt,
                                BigDecimal totalTvaSurCommissions,
                                BigDecimal soldeNetRemise) {
    }

    private record AmexTransaction(String submissionDate,
                                   String transactionDate,
                                   String cardNumber,
                                   BigDecimal submission,
                                   BigDecimal discount,
                                   BigDecimal net) {
    }
}
