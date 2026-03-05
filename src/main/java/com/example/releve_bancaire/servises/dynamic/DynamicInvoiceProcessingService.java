package com.example.releve_bancaire.servises.dynamic;

import com.example.releve_bancaire.dto.account_tier.TierDto;
import com.example.releve_bancaire.dto.ocr.OcrResult;
import com.example.releve_bancaire.entity.dynamic.DynamicInvoice;
import com.example.releve_bancaire.entity.dynamic.DynamicTemplate;
import com.example.releve_bancaire.entity.account_tier.Tier;
import com.example.releve_bancaire.entity.invoice.InvoiceStatus;
import com.example.releve_bancaire.entity.template.SignatureType;
import com.example.releve_bancaire.entity.template.TemplateSignature;
import com.example.releve_bancaire.repository.DynamicInvoiceDao;
import com.example.releve_bancaire.servises.FileStorageService;
import com.example.releve_bancaire.servises.account_tier.TierService;
import com.example.releve_bancaire.servises.ocr.AdvancedOcrService;
import com.example.releve_bancaire.servises.patterns.FieldPatternService;
import com.example.releve_bancaire.utils.ExtractionPatterns;
import com.example.releve_bancaire.utils.InvoiceTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service de traitement des factures dynamiques
 * VERSION CORRIGÃƒâ€°E:
 * - DÃƒÂ©tection ICE/IF/RC UNIQUEMENT dans le FOOTER (75% du bas)
 * - Auto-crÃƒÂ©ation template DÃƒâ€°SACTIVÃƒâ€°E
 * - VÃƒÂ©rification tier existant avant template
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DynamicInvoiceProcessingService {

    private final DynamicTemplateService dynamicTemplateService;
    private final DynamicFieldExtractorService dynamicFieldExtractorService;
    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final TierService tierService;
    private final AdvancedOcrService advancedOcrService;
    private final FileStorageService fileStorageService;
    private final FieldPatternService fieldPatternService;

    // CONFIGURATION: Pourcentage du texte considÃƒÂ©rÃƒÂ© comme footer
    private static final double FOOTER_START_PERCENTAGE = 0.75; // Footer commence ÃƒÂ  60%

    @Transactional
    public DynamicInvoice processInvoice(MultipartFile file, Long dossierId) throws IOException {
        log.info("=== DÃƒâ€°BUT TRAITEMENT FACTURE ===");
        log.info("Fichier: {}", file.getOriginalFilename());

        // Ãƒâ€°TAPE 1: STOCKAGE FICHIER
        String filePath = fileStorageService.store(file);
        Path path = Paths.get(filePath);
        log.info("Fichier stockÃƒÂ©: {}", filePath);

        // Ãƒâ€°TAPE 2: OCR AVANCÃƒâ€°
        log.info("Lancement OCR avancÃƒÂ©...");
        OcrResult ocrResult = advancedOcrService.extractTextAdvanced(path);

        // CORRECTION: Extraire UNIQUEMENT le texte
        String ocrText = ocrResult.getText();

        if (ocrText == null || ocrText.isBlank()) {
            log.warn("OCR ÃƒÂ©chouÃƒÂ©: Aucun texte extrait pour {}", file.getOriginalFilename());
            // Au lieu de throw, on crÃƒÂ©e l'entitÃƒÂ© avec un statut spÃƒÂ©cial pour
            // permettre la
            // saisie manuelle
            ocrText = ""; // Texte vide
        }

        log.info("OCR rÃƒÂ©ussi:");
        log.info("  - Texte: {} caractÃƒÂ¨res", ocrText.length());
        log.info("  - Confiance: {}%", ocrResult.getConfidence());
        log.info("  - Temps: {} ms", ocrResult.getProcessingTimeMs());
        log.info("  - Tentative: #{}", ocrResult.getAttemptNumber());

        // Ãƒâ€°TAPE 3: CRÃƒâ€°ATION ENTITY INVOICE
        DynamicInvoice invoice = new DynamicInvoice();

        // MÃƒÂ©tadonnÃƒÂ©es fichier
        invoice.setFilename(file.getOriginalFilename());
        invoice.setOriginalName(file.getOriginalFilename());
        invoice.setFilePath(filePath);
        invoice.setFileSize(file.getSize());

        // CORRECTION: Sauvegarder UNIQUEMENT le texte OCR
        invoice.setRawOcrText(ocrText);

        // MÃƒÂ©tadonnÃƒÂ©es OCR (optionnel: stocker dans extractedData)
        Map<String, Object> ocrMetadata = new HashMap<>();
        ocrMetadata.put("ocrConfidence", ocrResult.getConfidence());
        ocrMetadata.put("ocrProcessingTimeMs", ocrResult.getProcessingTimeMs());
        ocrMetadata.put("ocrAttemptNumber", ocrResult.getAttemptNumber());
        ocrMetadata.put("ocrImageWidth", ocrResult.getImageWidth());
        ocrMetadata.put("ocrImageHeight", ocrResult.getImageHeight());
        invoice.setExtractedData(ocrMetadata);

        invoice.setStatus(InvoiceStatus.PROCESSING);
        invoice.setCreatedAt(LocalDateTime.now());
        invoice.setUpdatedAt(LocalDateTime.now());
        invoice.setDossierId(dossierId);

        // Ãƒâ€°TAPE 4 ÃƒÂ  9: TRAITEMENT COMMUN
        return applyProcessingRules(invoice);
    }

    @Transactional
    public DynamicInvoice reprocessExistingInvoice(DynamicInvoice invoice) throws IOException {
        log.info("=== RETRAITMENT FACTURE EXISTANTE: {} ===", invoice.getId());

        if (invoice.getFilePath() == null) {
            throw new IllegalArgumentException("Chemin de fichier manquant pour la facture " + invoice.getId());
        }

        Path path = Paths.get(invoice.getFilePath());
        if (!Files.exists(path)) {
            throw new IOException("Fichier introuvable sur le disque: " + invoice.getFilePath());
        }

        // 1. RE-RUN OCR
        log.info("Lancement OCR (Retraitement)...");
        OcrResult ocrResult = advancedOcrService.extractTextAdvanced(path);
        String ocrText = ocrResult.getText();

        if (ocrText == null || ocrText.isBlank()) {
            log.warn("OCR ÃƒÂ©chouÃƒÂ© lors du retraitement pour facture {}", invoice.getId());
            ocrText = "";
        }

        // 2. Mettre ÃƒÂ  jour les donnÃƒÂ©es OCR
        invoice.setRawOcrText(ocrText);

        // Mettre ÃƒÂ  jour mÃƒÂ©tadonnÃƒÂ©es OCR si prÃƒÂ©sent dans extractedData
        Map<String, Object> ocrMetadata = new HashMap<>();
        if (invoice.getExtractedData() != null) {
            ocrMetadata.putAll(invoice.getExtractedData());
        }
        ocrMetadata.put("ocrConfidence", ocrResult.getConfidence());
        ocrMetadata.put("ocrProcessingTimeMs", ocrResult.getProcessingTimeMs());
        ocrMetadata.put("lastReprocessedAt", LocalDateTime.now());
        invoice.setExtractedData(ocrMetadata);

        // 3. RÃƒÂ©initialiser status et champs
        invoice.setStatus(InvoiceStatus.PROCESSING);
        invoice.setUpdatedAt(LocalDateTime.now());
        // On ne vide pas fieldsData tout de suite, ils seront
        // ÃƒÂ©crasÃƒÂ©s/fusionnÃƒÂ©s par
        // applyProcessingRules

        // 4. Lancer le pipeline
        return applyProcessingRules(invoice);
    }

    private DynamicInvoice applyProcessingRules(DynamicInvoice invoice) {
        String ocrText = invoice.getRawOcrText();

        // Ãƒâ€°TAPE 4: DÃƒâ€°TECTION SIGNATURE (IF/ICE/RC dans footer)
        log.info("DÃƒÂ©tection signature...");
        TemplateSignature signature = detectSignatureFromFooter(ocrText);
        invoice.setDetectedSignature(signature);

        if (signature != null) {
            log.info("Signature dÃƒÂ©tectÃƒÂ©e: {} = {}",
                    signature.getSignatureType(), signature.getSignatureValue());
        } else {
            log.warn("Aucune signature dÃƒÂ©tectÃƒÂ©e (IF/ICE/RC)");
        }

        // Ãƒâ€°TAPE 5: RECHERCHE TEMPLATE
        Optional<DynamicTemplate> templateOpt = dynamicTemplateService.detectTemplateBySignature(ocrText);
        DynamicTemplate template = null;

        if (templateOpt.isPresent()) {
            template = templateOpt.get();
            invoice.setTemplateId(template.getId());
            invoice.setTemplateName(template.getTemplateName());
            log.info("Template trouvÃƒÂ©: {} (ID={})", template.getTemplateName(), template.getId());
        } else {
            log.warn("Aucun template trouvÃƒÂ©");
        }

        // Ãƒâ€°TAPE 6: EXTRACTION CHAMPS
        log.info("Extraction des champs...");
        Map<String, Object> fieldsData = new LinkedHashMap<>();
        List<String> autoFilledFields = new ArrayList<>();

        DynamicExtractionResult extractionResult;
        if (template != null) {
            // Extraction avec template
            extractionResult = dynamicFieldExtractorService.extractWithTemplate(ocrText, template);
            fieldsData.putAll(extractionResult.toSimpleMap());

            // Auto-fill donnÃƒÂ©es fixes du template
            if (template.getFixedSupplierData() != null) {
                DynamicTemplate.FixedSupplierData fixed = template.getFixedSupplierData();

                if (fixed.getIce() != null && !fixed.getIce().isBlank()) {
                    fieldsData.put("ice", fixed.getIce());
                    autoFilledFields.add("ice");
                }
                if (fixed.getIfNumber() != null && !fixed.getIfNumber().isBlank()) {
                    fieldsData.put("ifNumber", fixed.getIfNumber());
                    autoFilledFields.add("ifNumber");
                }
                if (fixed.getRcNumber() != null && !fixed.getRcNumber().isBlank()) {
                    fieldsData.put("rcNumber", fixed.getRcNumber());
                    autoFilledFields.add("rcNumber");
                }
                if (fixed.getSupplier() != null && !fixed.getSupplier().isBlank()) {
                    fieldsData.put("supplier", fixed.getSupplier());
                    autoFilledFields.add("supplier");
                }
            }
        } else {
            // Extraction sans template
            extractionResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);
            fieldsData.putAll(extractionResult.toSimpleMap());
        }

        // Fallback: utiliser field_patterns pour les champs vides restants (y compris
        // invoiceDate).
        applyMissingFieldsFallback(ocrText, fieldsData, template, extractionResult);

        // Ãƒâ€°TAPE 7: LIAISON TIER AUTOMATIQUE
        log.info("Liaison Tier automatique...");
        linkInvoiceToTier(invoice, fieldsData, autoFilledFields);

        // Ãƒâ€°TAPE 8: CALCUL MONTANTS
        calculateAndValidateAmounts(fieldsData);

        // ETAPE 8B: DETECTION AVOIR
        invoice.setIsAvoir(InvoiceTypeDetector.isAvoir(fieldsData, invoice.getRawOcrText()));

        // Ãƒâ€°TAPE 9: FINALISATION
        invoice.setFieldsData(fieldsData);
        invoice.setAutoFilledFields(autoFilledFields);

        // DÃƒÂ©terminer status (Decision Matrix du Workflow)
        if (invoice.getRawOcrText().isBlank()) {
            invoice.setStatus(InvoiceStatus.ERROR);
            log.warn("Status: ERROR (Texte OCR vide)");
        } else if (invoice.getTier() != null && invoice.getTemplateId() != null && extractionResult.isComplete()) {
            invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);
            log.info("Status: READY_TO_VALIDATE");
        } else {
            invoice.setStatus(InvoiceStatus.TREATED);
            log.info("Status: TREATED (Manque Tier, Template ou champs requis)");
        }

        // Ãƒâ€°TAPE 10: SAUVEGARDE
        DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

        log.info("=== FIN TRAITEMENT FACTURE ===");
        log.info("Invoice ID: {}", saved.getId());
        log.info("Status: {}", saved.getStatus());
        log.info("Template: {}", saved.getTemplateName() != null ? saved.getTemplateName() : "Aucun");
        log.info("Tier: {}", saved.getTierName() != null ? saved.getTierName() : "Aucun");

        return saved;
    }

    // ===================== DÃƒâ€°TECTION SIGNATURE (FOOTER UNIQUEMENT)
    // =====================

    /**
     * DÃƒÂ©tecte la signature du fournisseur UNIQUEMENT dans le footer (60% du bas)
     * CRITIQUE: Ignore le header pour ÃƒÂ©viter de dÃƒÂ©tecter l'ICE du client
     */
    private TemplateSignature detectSignatureFromFooter(String text) {
        log.info("=== DETECTION SIGNATURES DANS LE FOOTER ===");

        String footer = extractFooter(text);
        log.info("Footer extrait: {} caracteres ({}% du bas)",
                footer.length(), (int) ((1 - FOOTER_START_PERCENTAGE) * 100));

        // Extraire TOUTES les signatures
        String ifNumber = extractIfFromFooter(footer);
        String ice = extractIceFromFooter(footer);
        String rc = extractRcFromFooter(footer);

        // Log results
        log.info("RÃƒÂ©sultats extraction footer: IF={}, ICE={}, RC={}", ifNumber, ice, rc);

        // Logger toutes les signatures dÃƒÂ©tectÃƒÂ©es
        log.info("Signatures detectees dans footer:");
        if (ifNumber != null)
            log.info("  - IF: {}", ifNumber);
        if (ice != null)
            log.info("  - ICE: {}", ice);
        if (rc != null)
            log.info("  - RC: {}", rc);

        // PRIORITÃƒâ€° 1: IF (plus fiable - unique)
        if (ifNumber != null && ifNumber.matches("\\d{7,10}")) {
            log.info("Signature retenue: IF:{}", ifNumber);
            return new TemplateSignature(SignatureType.IF, ifNumber);
        }

        // PRIORITÃƒâ€° 2: ICE (fallback)
        if (ice != null && ice.matches("\\d{15}")) {
            log.info("Signature retenue: ICE:{}", ice);
            return new TemplateSignature(SignatureType.ICE, ice);
        }

        // PRIORITÃƒâ€° 3: RC (Nouveau fallback)
        if (rc != null && !rc.isBlank()) {
            log.info("Signature retenue: RC:{}", rc);
            return new TemplateSignature(SignatureType.RC, rc);
        }

        log.warn("Aucune signature valide detectee");
        return null;
    }

    /**
     * Extrait le footer (40% du bas du texte, commenÃƒÂ§ant ÃƒÂ  60%)
     */
    /**
     * Extrait le footer en utilisant le marqueur [FOOTER] ou par pourcentage
     * (fallback)
     */
    private String extractFooter(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // Ã¢Å“â€¦ AMÃƒâ€°LIORATION: Utiliser le marqueur de zone si prÃƒÂ©sent
        if (text.contains("[FOOTER]")) {
            int footerIndex = text.indexOf("[FOOTER]");
            String footer = text.substring(footerIndex + "[FOOTER]".length()).trim();
            log.debug("Footer extrait via marqueur [FOOTER] ({} chars)", footer.length());
            return footer;
        }

        // Fallback par pourcentage
        int footerStartIndex = (int) (text.length() * FOOTER_START_PERCENTAGE);
        String footer = text.substring(footerStartIndex);

        log.debug("Footer extrait via pourcentage {}% ({} chars)",
                (int) (FOOTER_START_PERCENTAGE * 100), footer.length());

        return footer;
    }

    /**
     * Extrait l'ICE UNIQUEMENT dans le footer
     * IMPORTANT: Prend le DERNIER ICE trouvÃƒÂ© (= fournisseur, pas client)
     */
    private String extractIceFromFooter(String footer) {
        if (footer == null || footer.isEmpty()) {
            return null;
        }

        log.debug("Recherche ICE dans le footer ({} caracteres)", footer.length());

        List<String> allIceFound = new ArrayList<>();

        for (String patternStr : ExtractionPatterns.ICE_PATTERNS) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(footer);

            while (matcher.find()) {
                String ice = ExtractionPatterns.cleanNumber(matcher.group(1));
                if (ice.length() == 15 && ice.matches("\\d{15}")) {
                    allIceFound.add(ice);
                    log.debug("ICE candidat trouve: {}", ice);
                }
            }
        }

        if (!allIceFound.isEmpty()) {
            String lastIce = allIceFound.get(allIceFound.size() - 1);

            log.info("=== DETECTION ICE FINALE ===");
            log.info("Total ICE trouves dans le footer: {}", allIceFound.size());

            if (allIceFound.size() > 1) {
                log.warn("ATTENTION: Plusieurs ICE detectes:");
                for (int i = 0; i < allIceFound.size(); i++) {
                    String ice = allIceFound.get(i);
                    if (i == allIceFound.size() - 1) {
                        log.warn("  ICE #{}: {} <- FOURNISSEUR (RETENU)", i + 1, ice);
                    } else {
                        log.warn("  ICE #{}: {} <- Possiblement CLIENT (IGNORE)", i + 1, ice);
                    }
                }
            } else {
                log.info("ICE FOURNISSEUR unique: {}", lastIce);
            }

            return lastIce;
        }

        log.debug("Aucun ICE trouve dans le footer");
        return null;
    }

    /**
     * Extrait l'IF UNIQUEMENT dans le footer
     * IMPORTANT: Prend le DERNIER IF trouvÃƒÂ© (= fournisseur, pas client)
     */
    private String extractIfFromFooter(String footer) {
        if (footer == null || footer.isEmpty()) {
            return null;
        }

        log.debug("Recherche IF dans le footer ({} caracteres)", footer.length());

        List<String> allIfFound = new ArrayList<>();

        for (String patternStr : ExtractionPatterns.IF_PATTERNS) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(footer);

            while (matcher.find()) {
                String ifNum = ExtractionPatterns.cleanNumber(matcher.group(1));
                if (ifNum.matches("\\d{7,10}")) {
                    allIfFound.add(ifNum);
                    log.debug("IF candidat trouve: {}", ifNum);
                }
            }
        }

        if (!allIfFound.isEmpty()) {
            String lastIf = allIfFound.get(allIfFound.size() - 1);

            log.info("=== DETECTION IF FINALE ===");
            log.info("Total IF trouves dans le footer: {}", allIfFound.size());

            if (allIfFound.size() > 1) {
                log.warn("ATTENTION: Plusieurs IF detectes:");
                for (int i = 0; i < allIfFound.size(); i++) {
                    String ifNum = allIfFound.get(i);
                    if (i == allIfFound.size() - 1) {
                        log.warn("  IF #{}: {} <- FOURNISSEUR (RETENU)", i + 1, ifNum);
                    } else {
                        log.warn("  IF #{}: {} <- IGNORE", i + 1, ifNum);
                    }
                }
            } else {
                log.info("IF FOURNISSEUR unique: {}", lastIf);
            }

            return lastIf;
        }

        log.debug("Aucun IF trouve dans le footer");
        return null;
    }

    /**
     * Extrait le RC UNIQUEMENT dans le footer
     */
    private String extractRcFromFooter(String footer) {
        if (footer == null || footer.isEmpty()) {
            return null;
        }

        log.debug("Recherche RC dans le footer ({} caracteres)", footer.length());

        String lastRc = null;

        for (String patternStr : ExtractionPatterns.RC_PATTERNS) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(footer);

            while (matcher.find()) {
                lastRc = matcher.group(1);
                log.debug("RC candidat trouve: {}", lastRc);
            }
        }

        if (lastRc != null) {
            log.info("RC FOURNISSEUR final: {}", lastRc);
        } else {
            log.debug("Aucun RC trouve dans le footer");
        }

        return lastRc;
    }

    // ===================== LIAISON TIER (IF prioritaire, puis ICE)
    // =====================

    /**
     * Lie automatiquement la facture ÃƒÂ  un tier existant
     * PRIORITÃƒâ€° 1: Recherche par IF (plus fiable)
     * PRIORITÃƒâ€° 2: Recherche par ICE (fallback)
     */
    private void linkInvoiceToTier(DynamicInvoice invoice, Map<String, Object> fieldsData,
            List<String> autoFilledFields) {
        log.info("=== RECHERCHE TIER POUR LIAISON AUTOMATIQUE ===");

        String extractedIf = getStringValue(fieldsData, "ifNumber");
        String extractedIce = getStringValue(fieldsData, "ice");
        Long dossierId = invoice.getDossierId();

        Tier tier = null;

        // PRIORITÃƒâ€° 1: Chercher par IF
        if (dossierId != null && extractedIf != null && !extractedIf.isBlank()) {
            Optional<TierDto> tierDto = tierService.getTierByIfNumber(extractedIf, dossierId);
            if (tierDto.isPresent()) {
                tier = convertDtoToEntity(tierDto.get());
                log.info("Tier trouve par IF: {} (IF: {})", tier.getLibelle(), extractedIf);
            }
        }

        // PRIORITÃƒâ€° 2: Chercher par ICE
        if (tier == null && dossierId != null && extractedIce != null && !extractedIce.isBlank()) {
            Optional<TierDto> tierDto = tierService.getTierByIce(extractedIce, dossierId);
            if (tierDto.isPresent()) {
                tier = convertDtoToEntity(tierDto.get());
                log.info("Tier trouve par ICE: {} (ICE: {})", tier.getLibelle(), extractedIce);
            }
        }

        // Si trouvÃƒÂ©, lier ÃƒÂ  la facture
        if (tier != null) {
            invoice.setTier(tier);
            invoice.setTierId(tier.getId());
            invoice.setTierName(tier.getLibelle());

            log.info("Facture liÃƒÂ©e au tier: ID={}, Nom={}", tier.getId(), tier.getLibelle());

            // NOUVEAU : Remplacer supplier par Tier.libelle
            fieldsData.put("supplier", tier.getLibelle());
            autoFilledFields.add("supplier");
            log.info("Supplier remplacÃƒÂ© par Tier.libelle: {}", tier.getLibelle());

            // Auto-remplir les comptes comptables
            if (tier.hasAccountingConfiguration()) {
                fieldsData.put("tierNumber", tier.getTierNumber());
                autoFilledFields.add("tierNumber");

                if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                    fieldsData.put("collectifAccount", tier.getCollectifAccount());
                    autoFilledFields.add("collectifAccount");
                }

                fieldsData.put("chargeAccount", tier.getDefaultChargeAccount());
                fieldsData.put("tvaAccount", tier.getTvaAccount());
                fieldsData.put("tvaRate", tier.getDefaultTvaRate());

                autoFilledFields.add("chargeAccount");
                autoFilledFields.add("tvaAccount");
                autoFilledFields.add("tvaRate");

                log.info("Comptes comptables auto-remplis depuis le Tier:");
                log.info("  - tierNumber: {}", tier.getTierNumber());
                log.info("  - chargeAccount: {}", tier.getDefaultChargeAccount());
                log.info("  - tvaAccount: {}", tier.getTvaAccount());
            }
        } else {
            log.warn("Aucun Tier trouve pour cette facture");
            log.warn("   IF: {}", extractedIf != null ? extractedIf : "non dÃƒÂ©tectÃƒÂ©");
            log.warn("   ICE: {}", extractedIce != null ? extractedIce : "non dÃƒÂ©tectÃƒÂ©");
            log.info("Ã¢â€ â€™ L'utilisateur devra crÃƒÂ©er ou lier un tier manuellement");
        }
    }

    // ===================== OCR =====================

    private String performOcr(File file) throws Exception {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".pdf")) {
            log.info("PDF detecte, conversion en image...");
            File imageFile = convertPdfToImage(file);
            try {
                return String.valueOf(advancedOcrService.extractTextAdvanced(imageFile.toPath()));
            } finally {
                if (imageFile != null && imageFile.exists()) {
                    imageFile.delete();
                }
            }
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png")) {
            return String.valueOf(advancedOcrService.extractTextAdvanced(file.toPath()));
        } else {
            throw new IllegalArgumentException("Type de fichier non supporte: " + fileName);
        }
    }

    private File convertPdfToImage(File pdfFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            if (document.getNumberOfPages() == 0) {
                throw new IOException("Le PDF ne contient aucune page");
            }

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 300);

            File tempFile = File.createTempFile("invoice_", ".png");
            ImageIO.write(image, "PNG", tempFile);

            log.info("PDF converti: {}x{} pixels", image.getWidth(), image.getHeight());
            return tempFile;
        }
    }

    // ===================== UTILS =====================

    private Double extractDouble(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null)
            return null;

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        try {
            String str = value.toString()
                    .replaceAll("[^0-9,.]", "")
                    .replace(",", ".");
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Tier convertDtoToEntity(TierDto dto) {
        return Tier.builder()
                .id(dto.getId())
                .auxiliaireMode(dto.getAuxiliaireMode())
                .tierNumber(dto.getTierNumber())
                .collectifAccount(dto.getCollectifAccount())
                .libelle(dto.getLibelle())
                .ifNumber(dto.getIfNumber())
                .ice(dto.getIce())
                .rcNumber(dto.getRcNumber())
                .defaultChargeAccount(dto.getDefaultChargeAccount())
                .tvaAccount(dto.getTvaAccount())
                .defaultTvaRate(dto.getDefaultTvaRate())
                .active(dto.getActive())
                .build();
    }

    private void applyMissingFieldsFallback(
            String ocrText,
            Map<String, Object> fieldsData,
            DynamicTemplate template,
            DynamicExtractionResult extractionResult) {
        if (ocrText == null || ocrText.isBlank() || fieldsData == null) {
            return;
        }

        Set<String> candidates = new LinkedHashSet<>();

        if (extractionResult != null && extractionResult.getMissingFields() != null) {
            candidates.addAll(extractionResult.getMissingFields());
        }

        if (template != null && template.getFieldDefinitions() != null) {
            template.getFieldDefinitions().stream()
                    .map(DynamicTemplate.DynamicFieldDefinitionJson::getFieldName)
                    .filter(Objects::nonNull)
                    .forEach(candidates::add);
        }

        candidates.addAll(List.of(
                "invoiceNumber",
                "invoiceDate",
                "amountHT",
                "tva",
                "amountTTC",
                "ice",
                "ifNumber",
                "rcNumber",
                "supplier"));

        Map<String, Object> heuristicFallback = Collections.emptyMap();
        try {
            DynamicExtractionResult heuristicResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);
            if (heuristicResult != null) {
                heuristicFallback = heuristicResult.toSimpleMap();
            }
        } catch (Exception e) {
            log.warn("Fallback heuristique indisponible: {}", e.getMessage());
        }

        for (String fieldName : candidates) {
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }

            Object existing = fieldsData.get(fieldName);
            if (existing != null && !existing.toString().isBlank()) {
                continue;
            }

            Optional<String> dbMatch = fieldPatternService.extractFirstMatch(fieldName, ocrText);
            if (dbMatch.isPresent()) {
                String value = dbMatch.get();
                fieldsData.put(fieldName, value);
                log.info("Fallback field_patterns matched [{}] = {}", fieldName, value);
                continue;
            }

            Object heuristicValue = heuristicFallback.get(fieldName);
            if (heuristicValue != null && !heuristicValue.toString().isBlank()) {
                fieldsData.put(fieldName, heuristicValue);
                log.info("Fallback heuristique matched [{}] = {}", fieldName, heuristicValue);
            }
        }
    }

    private void calculateAndValidateAmounts(Map<String, Object> fieldsData) {
        log.info("=== CALCUL ET VALIDATION MONTANTS ===");

        List<Double> multiHt = parseMultiAmounts(fieldsData.get("htValues"));
        List<Double> multiTva = parseMultiAmounts(fieldsData.get("tvaValues"));
        if (multiHt.size() >= 2) {
            double htSum = round2(multiHt.stream().mapToDouble(Double::doubleValue).sum());
            fieldsData.put("amountHT", htSum);
            log.info("HT multi-lignes detecte -> amountHT recalcule: {}", htSum);
        }
        if (multiTva.size() >= 2) {
            double tvaSum = round2(multiTva.stream().mapToDouble(Double::doubleValue).sum());
            fieldsData.put("tva", tvaSum);
            log.info("TVA multi-lignes detectee -> tva recalculee: {}", tvaSum);
        }

        // Extraire les montants
        Double amountHT = extractDouble(fieldsData, "amountHT");
        Double tva = extractDouble(fieldsData, "tva");
        Double amountTTC = extractDouble(fieldsData, "amountTTC");
        boolean hasMultiLineAmounts = multiHt.size() >= 2 || multiTva.size() >= 2;

        log.debug("Montants extraits: HT={}, TVA={}, TTC={}", amountHT, tva, amountTTC);
        if (hasMultiLineAmounts && amountHT != null && tva != null) {
            double calculatedFromLines = round2(amountHT + tva);
            if (amountTTC == null || Math.abs(amountTTC - calculatedFromLines) > 0.01) {
                fieldsData.put("amountTTC", calculatedFromLines);
                log.info("TTC recalcule depuis montants multi-lignes: {}", calculatedFromLines);
                amountTTC = calculatedFromLines;
            }
        }
        if (!fieldsData.containsKey("__enableAmountAutoCalculation")) {
            log.info("Aucun montant n'est recalculÃ©: seules les valeurs extraites sont conservÃ©es.");
            return;
        }

        // CAS 1: Si HT et TVA prÃƒÂ©sents, calculer TTC thÃƒÂ©orique
        if (amountHT != null && tva != null) {
            Double calculatedTTC = amountHT + tva;
            log.info("TTC calculÃƒÂ©: {} (HT {} + TVA {})", calculatedTTC, amountHT, tva);

            if (amountTTC != null) {
                // Comparer avec TTC extrait (tolÃƒÂ©rance 0.01Ã¢â€šÂ¬)
                double difference = Math.abs(amountTTC - calculatedTTC);

                if (difference > 0.01) {
                    log.warn("Ãƒâ€°CART TTC DÃƒâ€°TECTÃƒâ€°:");
                    log.warn("   TTC extrait: {}", amountTTC);
                    log.warn("   TTC calculÃƒÂ©: {}", calculatedTTC);
                    log.warn("   Ãƒâ€°cart: {} Ã¢â€šÂ¬", difference);

                    // Utiliser le TTC calculÃƒÂ© (plus fiable)
                    fieldsData.put("amountTTC", calculatedTTC);
                    fieldsData.put("ttcDifference", difference);
                    log.info("TTC remplacÃƒÂ© par valeur calculÃƒÂ©e");
                } else {
                    log.info("TTC cohÃƒÂ©rent (ÃƒÂ©cart {} Ã¢â€šÂ¬)", difference);
                }
            } else {
                // TTC manquant Ã¢â€ â€™ Le calculer
                fieldsData.put("amountTTC", calculatedTTC);
                log.info("TTC calculÃƒÂ© automatiquement: {}", calculatedTTC);
            }
        }

        // CAS 2: Si HT et TTC prÃƒÂ©sents mais pas TVA, calculer TVA
        if (amountHT != null && amountTTC != null && tva == null) {
            Double calculatedTVA = amountTTC - amountHT;
            fieldsData.put("tva", calculatedTVA);
            log.info("TVA calculÃƒÂ©e automatiquement: {}", calculatedTVA);
        }

        // CAS 3: Si TVA et TTC prÃƒÂ©sents mais pas HT, calculer HT
        if (tva != null && amountTTC != null && amountHT == null) {
            Double calculatedHT = amountTTC - tva;
            fieldsData.put("amountHT", calculatedHT);
            log.info("HT calculÃƒÂ© automatiquement: {}", calculatedHT);
        }

        // VALIDATION: Taux de TVA
        if (amountHT != null && tva != null) {
            double tvaRate = (tva / amountHT) * 100.0;
            fieldsData.put("calculatedTvaRate", Math.round(tvaRate * 100.0) / 100.0);

            log.info("Taux TVA calculÃƒÂ©: {}%", tvaRate);

            // VÃƒÂ©rification des taux de TVA marocains standard (Workflow #1)
            double foundRate = 0.0;
            boolean standardRate = false;

            if (Math.abs(tvaRate - 20.0) < 0.5) {
                foundRate = 20.0;
                standardRate = true;
            } else if (Math.abs(tvaRate - 14.0) < 0.5) {
                foundRate = 14.0;
                standardRate = true;
            } else if (Math.abs(tvaRate - 10.0) < 0.5) {
                foundRate = 10.0;
                standardRate = true;
            } else if (Math.abs(tvaRate - 7.0) < 0.5) {
                foundRate = 7.0;
                standardRate = true;
            } else if (Math.abs(tvaRate - 0.0) < 0.1) {
                foundRate = 0.0;
                standardRate = true;
            }

            if (standardRate) {
                log.info("Taux TVA standard dÃƒÂ©tectÃƒÂ©: {}%", foundRate);
                fieldsData.put("tvaRate", foundRate);
            } else {
                log.warn("Taux TVA non standard: {}%", tvaRate);
                fieldsData.put("hasValidationWarning", true);
                fieldsData.put("validationWarningMessage",
                        "Taux TVA non standard dÃƒÂ©tectÃƒÂ©: " + Math.round(tvaRate) + "%");
            }
        }

        log.info("=== FIN CALCUL MONTANTS ===");
    }

    private List<Double> parseMultiAmounts(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }

        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return Collections.emptyList();
        }

        List<Double> values = new ArrayList<>();
        String[] tokens = text.split("\\|");
        for (String token : tokens) {
            Double value = extractAmount(token);
            if (value != null && value > 0) {
                values.add(round2(value));
            }
        }
        return values;
    }

    private Double extractAmount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text
                .replace("\u00A0", "")
                .replace(" ", "")
                .replace(",", ".")
                .replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
