package com.example.releve_bancaire.servises.ocr;

import com.example.releve_bancaire.dto.ocr.OcrResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.opencv.core.Mat;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdvancedOcrService {

    private final ImagePreprocessingService preprocessingService;
    private final TesseractConfigService tesseractConfigService;
    private final OcrValidationService ocrValidationService;
    private final OcrFallbackStrategy ocrFallbackStrategy;
    private final OcrPostProcessor ocrPostProcessor;

    private static final int MIN_TEXT_LENGTH = 100;
    private static final double MIN_CONFIDENCE = 60.0;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int MAX_OCR_CALLS_PER_INVOICE = 4;
    private static final long MAX_TOTAL_OCR_TIME_MS = 30_000L;
    private static final int IMAGE_MAX_OCR_CALLS = 3;
    private static final long IMAGE_MAX_TOTAL_OCR_TIME_MS = 8_000L;
    private static final long IMAGE_FAST_MAX_PIXELS = 1_200_000L;
    private static final int IMAGE_FAST_MAX_DIMENSION = 1400;

    // Zone segmentation configuration
    private static final double HEADER_HEIGHT_RATIO = 0.30; // 30% top
    private static final double BODY_HEIGHT_RATIO = 0.40; // 40% middle
    private static final double FOOTER_HEIGHT_RATIO = 0.30; // 30% bottom
    private static final String[] FOOTER_KEYWORDS = { "ICE", "I.F", " IF", "RC", "R.C", "CNSS", "PATENTE", "IF",
            "IDENTIFIANT", "FISCAL" };
    private static final int PDF_RENDER_DPI = 240;
    private static final long MAX_IMAGE_PIXELS = 7_000_000;
    private static final int MAX_IMAGE_DIMENSION = 3200;

    private static final Pattern REQUIRED_DATE = Pattern.compile("\\b\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}\\b");
    private static final Pattern REQUIRED_TOTAL = Pattern.compile("(?i)\\b(total|ttc|montant)\\b");
    private static final Pattern REQUIRED_ICE_IF = Pattern
            .compile("(?i)\\b(ICE|I\\.?F\\.?|IF|IDENTIFIANT\\s+FISCAL)\\b|\\b\\d{15}\\b|\\b\\d{7,10}\\b");
    private static final Pattern REQUIRED_INVOICE_NUMBER = Pattern.compile(
            "(?i)\\b(facture|invoice|reference|ref\\.?|num\\.?\\s*de\\s*facture|n\\s*[°ºo#])\\b.{0,30}[A-Z0-9][A-Z0-9\\-/]{2,}");

    /**
     * Zone types for segmented OCR
     */
    private enum ZoneType {
        HEADER, // 0-30%: Supplier, Invoice #, Date
        BODY, // 30-70%: Line items, tables
        FOOTER // 70-100%: ICE, IF, RC, amounts
    }

    public OcrResult extractTextAdvanced(Path imagePath) {
        log.info("=== DEBUT EXTRACTION OCR AVANCEE ===");
        log.info("Fichier: {}", imagePath.getFileName());

        long startTime = System.currentTimeMillis();
        long fileSizeBytes = -1L;
        try {
            fileSizeBytes = java.nio.file.Files.size(imagePath);
        } catch (Exception ignored) {
        }

        try {
            String filename = imagePath.getFileName().toString().toLowerCase();
            if (filename.endsWith(".pdf")) {
                OcrRuntimeContext context = new OcrRuntimeContext(
                        MAX_OCR_CALLS_PER_INVOICE,
                        MAX_TOTAL_OCR_TIME_MS,
                        false);
                OcrResult result = extractTextFromPdf(imagePath, startTime, context, fileSizeBytes);
                enrichTelemetry(result, context, fileSizeBytes, result.getImageWidth(), result.getImageHeight(),
                        result.getImageWidth(), result.getImageHeight(), filename);
                return result;
            }

            OcrRuntimeContext context = new OcrRuntimeContext(
                    IMAGE_MAX_OCR_CALLS,
                    IMAGE_MAX_TOTAL_OCR_TIME_MS,
                    true);
            BufferedImage originalImage = loadImage(imagePath);
            int beforeW = originalImage.getWidth();
            int beforeH = originalImage.getHeight();
            OcrResult result = extractTextFromSingleImage(originalImage, context);
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            enrichTelemetry(result, context, fileSizeBytes, beforeW, beforeH, result.getImageWidth(),
                    result.getImageHeight(),
                    filename);
            return result;
        } catch (Exception e) {
            log.error("Erreur critique OCR: {}", e.getMessage(), e);
            return OcrResult.failed(e.getMessage());
        }
    }

    private OcrResult extractTextFromPdf(Path imagePath, long startTime, OcrRuntimeContext context, long fileSizeBytes)
            throws IOException {
        log.info("PDF detecte - tentative extraction texte native...");

        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument
                .load(imagePath.toFile())) {
            int pageCount = document.getNumberOfPages();
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String nativeText = stripper.getText(document);

            if (nativeText != null && nativeText.trim().length() > 100) {
                boolean enrichFooter = shouldEnrichPdfFooter(nativeText);
                String mergedText = enrichFooter ? mergeNativePdfWithOcr(document, nativeText) : nativeText;
                long processingTime = System.currentTimeMillis() - startTime;

                log.info("PDF digital: {} caracteres extraits sans OCR", nativeText.length());
                if (enrichFooter && !mergedText.equals(nativeText)) {
                    log.info("Enrichissement OCR PDF: {} -> {} caracteres", nativeText.length(), mergedText.length());
                }

                return OcrResult.builder()
                        .text(mergedText)
                        .confidence(mergedText.equals(nativeText) ? 100.0 : 96.0)
                        .imageWidth(0)
                        .imageHeight(0)
                        .processingTimeMs(processingTime)
                        .success(true)
                        .telemetry(new LinkedHashMap<>(Map.of(
                                "pageCount", pageCount,
                                "fileSizeBytes", fileSizeBytes)))
                        .build();
            }

            log.info("PDF scanne ou texte insuffisant ({} chars) -> OCR multi-pages",
                    nativeText != null ? nativeText.trim().length() : 0);

            OcrResult result = extractFromScannedPdf(document, context);
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            result.getTelemetry().put("pageCount", pageCount);
            result.getTelemetry().put("fileSizeBytes", fileSizeBytes);
            return result;
        } catch (Exception e) {
            log.warn("Echec extraction native PDF: {} -> fallback OCR multi-pages", e.getMessage());
            try (org.apache.pdfbox.pdmodel.PDDocument fallbackDocument = org.apache.pdfbox.pdmodel.PDDocument
                    .load(imagePath.toFile())) {
                OcrResult result = extractFromScannedPdf(fallbackDocument, context);
                result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                result.getTelemetry().put("pageCount", fallbackDocument.getNumberOfPages());
                result.getTelemetry().put("fileSizeBytes", fileSizeBytes);
                return result;
            }
        }
    }

    private OcrResult extractFromScannedPdf(org.apache.pdfbox.pdmodel.PDDocument document, OcrRuntimeContext context)
            throws IOException {
        int pageCount = document.getNumberOfPages();
        if (pageCount <= 0) {
            return OcrResult.failed("Le PDF ne contient aucune page");
        }

        org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
        log.info("OCR multi-pages active: {} page(s)", pageCount);

        StringBuilder mergedText = new StringBuilder();
        double weightedConfidence = 0.0;
        int totalWeight = 0;

        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            if (!context.canRunAnotherCall()) {
                log.warn("Budget OCR atteint avant page {}/{}", pageIndex + 1, pageCount);
                break;
            }
            log.info("Traitement OCR page {}/{}", pageIndex + 1, pageCount);
            BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, PDF_RENDER_DPI);
            OcrResult pageResult = extractTextFromSingleImage(pageImage, context, true);

            String pageText = pageResult.getText() != null ? pageResult.getText().trim() : "";
            if (!pageText.isBlank()) {
                if (mergedText.length() > 0) {
                    mergedText.append("\n\n");
                }
                mergedText.append("[PAGE ").append(pageIndex + 1).append("]\n").append(pageText);
            }

            int pageWeight = Math.max(pageText.length(), 1);
            weightedConfidence += pageResult.getConfidence() * pageWeight;
            totalWeight += pageWeight;

            // Early stop for multi-page scanned PDFs:
            // once key invoice signals are present, avoid OCR on remaining pages.
            if (hasRequiredFields(mergedText.toString())) {
                log.info("Champs requis trouves apres page {}/{} -> arret OCR multi-pages", pageIndex + 1, pageCount);
                break;
            }
        }

        if (mergedText.length() == 0) {
            return OcrResult.failed("Aucun texte extrait des pages PDF");
        }

        double finalConfidence = totalWeight > 0 ? weightedConfidence / totalWeight : 0.0;
        return OcrResult.builder()
                .text(mergedText.toString())
                .confidence(finalConfidence)
                .imageWidth(0)
                .imageHeight(0)
                .success(true)
                .build();
    }

    private OcrResult extractTextFromSingleImage(BufferedImage originalImage, OcrRuntimeContext context) {
        return extractTextFromSingleImage(originalImage, context, false);
    }

    private OcrResult extractTextFromSingleImage(BufferedImage originalImage, OcrRuntimeContext context,
            boolean multiPageMode) {
        int beforeW = originalImage.getWidth();
        int beforeH = originalImage.getHeight();
        BufferedImage normalized = context.isImageFastMode()
                ? enforceOcrInputCapFast(originalImage)
                : enforceOcrInputCap(originalImage);
        if (!context.isImageFastMode()) {
            try {
                normalized = preprocessingService.deskewBufferedImage(normalized);
            } catch (Exception e) {
                log.debug("Deskew ignore: {}", e.getMessage());
            }
        }
        normalized = context.isImageFastMode()
                ? enforceOcrInputCapFast(normalized)
                : enforceOcrInputCap(normalized);
        log.info("Image normalisee: {}x{} -> {}x{}", beforeW, beforeH, normalized.getWidth(), normalized.getHeight());

        if (multiPageMode) {
            return extractTextFromSingleImageMultiPageMode(normalized, context);
        }

        if (context.isImageFastMode()) {
            return extractTextFromSingleImageFastMode(normalized, context);
        }

        OcrResult zonedResult = performZonedOcr(normalized, context);
        boolean requiredMissing = zonedResult == null || !hasRequiredFields(zonedResult.getText());

        if (!requiredMissing && zonedResult != null && zonedResult.isSuccess()) {
            if (!context.canRunAnotherCall()) {
                zonedResult.setConfidence(Math.min(zonedResult.getConfidence(), 55.0));
            }
            return zonedResult;
        }

        if (!requiredMissing) {
            return zonedResult != null ? zonedResult : OcrResult.failed("OCR zoned failed");
        }

        log.warn("Champs requis manquants apres zoned OCR -> fallback full-page conditionnel");
        context.setFallbackTriggered(true);
        OcrResult fallback = performFullPageFallback(normalized, context);
        OcrResult best = pickBestResult(zonedResult, fallback);
        if (best == null) {
            return OcrResult.failed("Aucun texte extrait");
        }
        if (!context.canRunAnotherCall()) {
            best.setConfidence(Math.min(best.getConfidence(), 55.0));
        }
        return best;
    }

    private OcrResult extractTextFromSingleImageMultiPageMode(BufferedImage image, OcrRuntimeContext context) {
        long startTime = System.currentTimeMillis();
        List<OcrResult> results = new ArrayList<>();

        OcrResult originalResult = performOcrWithConfig(image, 0, context, "multipage_fullpage");
        results.add(originalResult);
        if (hasRequiredFields(originalResult.getText()) || !context.canRunAnotherCall()) {
            originalResult.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            return originalResult;
        }

        try {
            Mat mat = preprocessingService.bufferedImageToMat(image);
            Mat lab = preprocessingService.labColorSpacePipeline(mat.clone());
            BufferedImage labImage = enforceOcrInputCap(preprocessingService.matToBufferedImage(lab));
            OcrResult labResult = performOcrWithConfig(labImage, 1, context, "multipage_fullpage");
            results.add(labResult);
        } catch (Exception e) {
            log.debug("Variant LAB ignoree en mode multipage: {}", e.getMessage());
        }

        OcrResult best = selectBestResult(results);
        best.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        return best;
    }

    /**
     * Charge une image depuis le filesystem
     * Supporte les images (PNG, JPG) et les PDFs (convertis en image)
     */
    private BufferedImage loadImage(Path imagePath) throws IOException {
        File imageFile = imagePath.toFile();
        if (!imageFile.exists()) {
            throw new IOException("Fichier introuvable: " + imagePath);
        }

        String filename = imagePath.getFileName().toString().toLowerCase();

        // CAS 1: PDF → Essayer extraction texte native AVANT OCR
        if (filename.endsWith(".pdf")) {
            log.info("Détection PDF, tentative extraction texte native...");
            try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument.load(imageFile)) {

                // ÉTAPE 1: Essayer extraction texte native (PDFs digitaux)
                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                String nativeText = stripper.getText(document);

                // Si texte natif suffisant (>100 caractères), pas besoin d'OCR !
                if (nativeText != null && nativeText.trim().length() > 100) {
                    log.info("PDF DIGITAL détecté: {} caractères extraits SANS OCR", nativeText.length());
                    log.info(" Gain de temps: OCR évité (PDF contient du texte natif)");

                    // Créer une image blanche avec le texte (pour compatibilité avec le pipeline)
                    // Le texte sera retourné directement dans extractTextAdvanced
                    BufferedImage dummyImage = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);

                    // Stocker le texte natif dans un attribut temporaire
                    // Note: On pourrait améliorer en créant un champ de classe
                    log.debug("Texte natif extrait (preview): {}",
                            nativeText.substring(0, Math.min(200, nativeText.length())));

                    // Pour l'instant, on continue avec OCR mais on log qu'on pourrait l'éviter
                    // TODO: Refactorer pour retourner directement le texte sans passer par OCR
                }

                // ÉTAPE 2: Si texte natif insuffisant → Conversion en image pour OCR
                log.info("PDF scanné ou texte insuffisant, conversion en image pour OCR...");
                org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(
                        document);
                // 300 DPI = meilleur équilibre qualité/performance
                BufferedImage image = renderer.renderImageWithDPI(0, PDF_RENDER_DPI);
                log.info("PDF converti: {}x{} pixels @ {} DPI",
                        image.getWidth(), image.getHeight(), PDF_RENDER_DPI);
                return image;

            } catch (Exception e) {
                throw new IOException("Erreur traitement PDF: " + e.getMessage(), e);
            }
        }

        // CAS 2: Image standard
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("Format d'image non supporté: " + imagePath);
        }

        return image;
    }

    private BufferedImage downscaleIfNeeded(BufferedImage image) {
        if (image == null) {
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * (long) height;

        if (pixels <= MAX_IMAGE_PIXELS && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
            return image;
        }

        double scaleByPixels = Math.sqrt((double) MAX_IMAGE_PIXELS / (double) pixels);
        double scaleByDimension = Math.min(
                (double) MAX_IMAGE_DIMENSION / (double) width,
                (double) MAX_IMAGE_DIMENSION / (double) height);
        double scale = Math.min(scaleByPixels, scaleByDimension);

        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage scaled = new BufferedImage(newWidth, newHeight,
                image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();

        log.info("Downscale image: {}x{} -> {}x{} (pixels: {} -> {})",
                width, height, newWidth, newHeight, pixels, (long) newWidth * newHeight);

        return scaled;
    }

    private boolean isLargeImage(BufferedImage image) {
        if (image == null) {
            return false;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * (long) height;
        return pixels > MAX_IMAGE_PIXELS || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION;
    }

    /**
     * Exécution OCR avec configuration spécifique
     */
    private OcrResult performOcrWithConfig(BufferedImage image, int attemptNumber, OcrRuntimeContext context,
            String stage) {
        if (image == null) {
            return OcrResult.failed("Image OCR nulle");
        }
        if (!context.canRunAnotherCall()) {
            return OcrResult.failed("Budget OCR atteint");
        }
        try {
            BufferedImage capped = enforceOcrInputCap(image);
            Tesseract tesseract = context.isImageFastMode()
                    ? tesseractConfigService.createFastImageInstance(attemptNumber)
                    : tesseractConfigService.createConfiguredInstance(attemptNumber);
            long callStart = System.currentTimeMillis();
            String text = tesseract.doOCR(capped);
            long callDuration = System.currentTimeMillis() - callStart;
            double confidence = estimateConfidence(text);

            OcrResult result = OcrResult.builder()
                    .text(text != null ? text : "")
                    .confidence(confidence)
                    .attemptNumber(attemptNumber + 1)
                    .imageWidth(capped.getWidth())
                    .imageHeight(capped.getHeight())
                    .success(true)
                    .build();
            result.setProcessingTimeMs(callDuration);
            context.recordCall(callDuration, stage, attemptNumber + 1, capped.getWidth(), capped.getHeight());
            return result;
        } catch (TesseractException e) {
            log.error("Erreur Tesseract tentative #{}: {}", attemptNumber + 1, e.getMessage());
            return OcrResult.builder()
                    .text("")
                    .confidence(0.0)
                    .attemptNumber(attemptNumber + 1)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * Estimation confiance basée sur heuristiques
     *
     * Critères:
     * - Longueur texte
     * - Ratio caractères alphanumériques
     * - Présence mots-clés facture (FACTURE, TOTAL, etc.)
     */
    private double estimateConfidence(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        double score = 0.0;

        // Critère 1: Longueur (max 40 points)
        int length = text.length();
        if (length > 500)
            score += 40;
        else if (length > 200)
            score += 30;
        else if (length > 100)
            score += 20;
        else
            score += 10;

        // Critère 2: Ratio alphanumérique (max 30 points)
        long alphanumCount = text.chars().filter(Character::isLetterOrDigit).count();
        double alphanumRatio = (double) alphanumCount / text.length();
        score += alphanumRatio * 30;

        // Critère 3: Mots-clés facture (max 30 points)
        String upperText = text.toUpperCase();
        int keywordCount = 0;
        String[] keywords = { "FACTURE", "INVOICE", "TOTAL", "TVA", "DATE", "MONTANT", "HT", "TTC" };
        for (String keyword : keywords) {
            if (upperText.contains(keyword))
                keywordCount++;
        }
        score += Math.min(keywordCount * 5, 30);

        return Math.min(score, 100.0);
    }

    /**
     * Sélectionne le meilleur résultat parmi les tentatives
     */
    private OcrResult selectBestResult(List<OcrResult> results) {
        return results.stream()
                .filter(r -> r.getText() != null && !r.getText().isBlank())
                .max(Comparator.comparingDouble(r -> r.getConfidence() * 0.7 + (r.getText().length() / 10.0) * 0.3))
                .orElse(OcrResult.failed("Aucun texte extrait"));
    }

    /**
     * PERFORM ZONED OCR: Main method for zone-based extraction
     * Segments image into Header/Body/Footer and performs targeted OCR
     */
    private OcrResult performZonedOcr(BufferedImage originalImage, OcrRuntimeContext context) {
        try {
            log.info("=== DEBUT OCR PAR ZONES (footer -> header -> body on-demand) ===");
            BufferedImage headerZone = extractZone(originalImage, ZoneType.HEADER);
            BufferedImage bodyZone = extractZone(originalImage, ZoneType.BODY);
            BufferedImage footerZone = extractZone(originalImage, ZoneType.FOOTER);

            OcrResult footerResult = ocrFooter(footerZone, context, false);
            OcrResult headerResult = ocrHeader(headerZone, context, false);
            OcrResult bodyResult = emptyZoneResult(bodyZone);

            OcrResult merged = mergeZoneResults(headerResult, bodyResult, footerResult);
            if (!hasRequiredFields(merged.getText())) {
                bodyResult = ocrBody(bodyZone, context, false);
                merged = mergeZoneResults(headerResult, bodyResult, footerResult);
            }

            if (!context.isImageFastMode() && !hasRequiredFields(merged.getText())) {
                OcrResult heavyFooter = ocrFooter(footerZone, context, true);
                if (isUsable(heavyFooter)) {
                    footerResult = heavyFooter;
                }
                OcrResult heavyHeader = ocrHeader(headerZone, context, true);
                if (isUsable(heavyHeader)) {
                    headerResult = heavyHeader;
                }
                merged = mergeZoneResults(headerResult, bodyResult, footerResult);
            }

            return merged;
        } catch (Exception e) {
            log.error("Erreur OCR par zones: {}", e.getMessage(), e);
            return OcrResult.failed("OCR zoned failed: " + e.getMessage());
        }
    }

    /**
     * ZONE SEGMENTATION: Extrait une zone spécifique de l'image
     * 
     * @param image Image source
     * @param zone  Type de zone à extraire
     * @return BufferedImage de la zone extraite
     */
    private BufferedImage extractZone(BufferedImage image, ZoneType zone) {
        int height = image.getHeight();
        int width = image.getWidth();
        int startY, zoneHeight;

        switch (zone) {
            case HEADER:
                startY = 0;
                zoneHeight = (int) (height * HEADER_HEIGHT_RATIO);
                log.debug("Extraction HEADER: 0-{}% ({}px)", (int) (HEADER_HEIGHT_RATIO * 100), zoneHeight);
                break;

            case BODY:
                startY = (int) (height * HEADER_HEIGHT_RATIO);
                zoneHeight = (int) (height * BODY_HEIGHT_RATIO);
                log.debug("Extraction BODY: {}-{}% ({}px)",
                        (int) (HEADER_HEIGHT_RATIO * 100),
                        (int) ((HEADER_HEIGHT_RATIO + BODY_HEIGHT_RATIO) * 100),
                        zoneHeight);
                break;

            case FOOTER:
                startY = (int) (height * (HEADER_HEIGHT_RATIO + BODY_HEIGHT_RATIO));
                zoneHeight = (int) (height * FOOTER_HEIGHT_RATIO);
                log.debug("Extraction FOOTER: {}-100% ({}px)",
                        (int) ((HEADER_HEIGHT_RATIO + BODY_HEIGHT_RATIO) * 100),
                        zoneHeight);
                break;

            default:
                throw new IllegalArgumentException("Zone type non supporté: " + zone);
        }

        // Sécurité: vérifier les limites
        if (startY + zoneHeight > height) {
            zoneHeight = height - startY;
            log.warn("Ajustement hauteur zone {} pour éviter débordement", zone);
        }

        return image.getSubimage(0, startY, width, zoneHeight);
    }

    /**
     * ZONE-SPECIFIC OCR: Header (Supplier, Invoice #, Date)
     * Pipelines: LAB (priority), Standard
     */
    private OcrResult ocrHeader(BufferedImage headerZone, OcrRuntimeContext context, boolean heavyPass) {
        long startTime = System.currentTimeMillis();
        try {
            List<BufferedImage> variants = new ArrayList<>();
            variants.add(enforceOcrInputCap(headerZone));
            Mat headerMat = preprocessingService.bufferedImageToMat(headerZone);
            Mat labProcessed = preprocessingService.labColorSpacePipeline(headerMat.clone());
            variants.add(enforceOcrInputCap(preprocessingService.matToBufferedImage(labProcessed)));
            if (heavyPass) {
                Mat standardProcessed = preprocessingService.standardPipeline(headerMat.clone());
                variants.add(enforceOcrInputCap(preprocessingService.matToBufferedImage(standardProcessed)));
            }
            return runVariants("header", variants, context, startTime, true);
        } catch (Exception e) {
            log.error("Erreur OCR header: {}", e.getMessage());
            return OcrResult.failed("Header OCR failed: " + e.getMessage());
        }
    }

    /**
     * ZONE-SPECIFIC OCR: Body (Line items, tables)
     * Pipelines: Hybrid LAB+Morphology (priority), LAB, Standard
     */
    private OcrResult ocrBody(BufferedImage bodyZone, OcrRuntimeContext context, boolean heavyPass) {
        long startTime = System.currentTimeMillis();
        try {
            List<BufferedImage> variants = new ArrayList<>();
            variants.add(enforceOcrInputCap(bodyZone));
            Mat bodyMat = preprocessingService.bufferedImageToMat(bodyZone);
            Mat labProcessed = preprocessingService.labColorSpacePipeline(bodyMat.clone());
            variants.add(enforceOcrInputCap(preprocessingService.matToBufferedImage(labProcessed)));
            if (heavyPass) {
                Mat hybridProcessed = preprocessingService.hybridLabMorphologyPipeline(bodyMat.clone());
                variants.add(enforceOcrInputCap(preprocessingService.matToBufferedImage(hybridProcessed)));
            }
            return runVariants("body", variants, context, startTime, false);
        } catch (Exception e) {
            log.error("Erreur OCR body: {}", e.getMessage());
            return OcrResult.failed("Body OCR failed: " + e.getMessage());
        }
    }

    /**
     * ZONE-SPECIFIC OCR: Footer (ICE, IF, RC, amounts)
     * Pipelines: LAB (priority), Standard, Heavy Denoising
     */
    private OcrResult ocrFooter(BufferedImage footerZone, OcrRuntimeContext context, boolean heavyPass) {
        long startTime = System.currentTimeMillis();
        try {
            List<BufferedImage> variants = new ArrayList<>();
            variants.add(enforceOcrInputCap(footerZone));
            Mat footerMat = preprocessingService.bufferedImageToMat(footerZone);
            Mat labProcessed = preprocessingService.labColorSpacePipeline(footerMat.clone());
            variants.add(enforceOcrInputCap(preprocessingService.matToBufferedImage(labProcessed)));
            if (heavyPass) {
                Mat denoisedProcessed = preprocessingService.heavyDenoisingPipeline(footerMat.clone());
                variants.add(enforceOcrInputCap(preprocessingService.matToBufferedImage(denoisedProcessed)));
            }
            return runVariants("footer", variants, context, startTime, false);
        } catch (Exception e) {
            log.error("Erreur OCR footer: {}", e.getMessage());
            return OcrResult.failed("Footer OCR failed: " + e.getMessage());
        }
    }

    private OcrResult runVariants(String stage, List<BufferedImage> variants, OcrRuntimeContext context, long startTime,
            boolean headerValidation) {
        List<OcrResult> results = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            if (!context.canRunAnotherCall()) {
                break;
            }
            OcrResult result = performOcrWithConfig(variants.get(i), i, context, stage);
            results.add(result);

            if (isStageSufficient(stage, result.getText())) {
                result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                return result;
            }

            boolean highQuality = headerValidation
                    ? ocrValidationService.isHighQualityHeader(result)
                    : ocrValidationService.isHighQuality(result);
            if (highQuality) {
                result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                return result;
            }
        }
        OcrResult best = selectBestResult(results);
        best.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        return best;
    }

    private OcrResult performFullPageFallback(BufferedImage originalImage, OcrRuntimeContext context) {
        List<OcrResult> results = new ArrayList<>();
        List<BufferedImage> preprocessedVariants = context.isImageFastMode()
                ? preprocessImageFast(originalImage)
                : preprocessingService.preprocessImage(originalImage);
        for (int i = 0; i < preprocessedVariants.size(); i++) {
            if (!context.canRunAnotherCall()) {
                break;
            }
            OcrResult result = performOcrWithConfig(preprocessedVariants.get(i), Math.min(i, 1), context, "fullpage");
            results.add(result);
            if (ocrValidationService.isHighQuality(result)) {
                return result;
            }
        }
        OcrResult best = selectBestResult(results);
        best.setConfidence(Math.min(best.getConfidence(), 55.0));
        return best;
    }

    private boolean isStageSufficient(String stage, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if ("header".equals(stage)) {
            return REQUIRED_DATE.matcher(text).find() || REQUIRED_INVOICE_NUMBER.matcher(text).find();
        }
        if ("footer".equals(stage)) {
            return REQUIRED_TOTAL.matcher(text).find() || REQUIRED_ICE_IF.matcher(text).find();
        }
        return false;
    }

    private OcrResult extractTextFromSingleImageFastMode(BufferedImage image, OcrRuntimeContext context) {
        long startTime = System.currentTimeMillis();
        List<OcrResult> results = new ArrayList<>();

        OcrResult fullOriginal = performOcrWithConfig(image, 0, context, "image_fast_full");
        results.add(fullOriginal);
        if (hasRequiredFieldsFast(fullOriginal.getText()) || !context.canRunAnotherCall()) {
            fullOriginal.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            return fullOriginal;
        }

        BufferedImage footerZone = extractZone(image, ZoneType.FOOTER);
        OcrResult footerOriginal = performOcrWithConfig(footerZone, 0, context, "image_fast_footer");
        results.add(footerOriginal);
        String mergedAfterFooter = mergeFastImageText(fullOriginal.getText(), footerOriginal.getText());
        if (hasRequiredFieldsFast(mergedAfterFooter) || !context.canRunAnotherCall()) {
            OcrResult best = selectBestResult(results);
            best.setText(mergedAfterFooter);
            best.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            return best;
        }

        if (!context.canRunAnotherCall()) {
            OcrResult best = selectBestResult(results);
            best.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            return best;
        }

        try {
            Mat footerMat = preprocessingService.bufferedImageToMat(footerZone);
            Mat footerLab = preprocessingService.labColorSpacePipeline(footerMat);
            BufferedImage footerLabImage = enforceOcrInputCapFast(preprocessingService.matToBufferedImage(footerLab));
            OcrResult footerLabResult = performOcrWithConfig(footerLabImage, 1, context, "image_fast_footer");
            results.add(footerLabResult);
        } catch (Exception e) {
            log.debug("Footer LAB fast mode ignore: {}", e.getMessage());
        }

        OcrResult best = selectBestResult(results);
        String combined = mergeFastImageText(fullOriginal.getText(), best.getText());
        best.setText(combined);
        best.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        return best;
    }

    private String mergeFastImageText(String fullText, String footerText) {
        String full = fullText != null ? fullText.trim() : "";
        String footer = footerText != null ? footerText.trim() : "";
        if (footer.isBlank()) {
            return full;
        }
        if (full.isBlank()) {
            return "[FOOTER]\n" + footer;
        }
        return full + "\n\n[FOOTER]\n" + footer;
    }

    private boolean hasRequiredFieldsFast(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hasDate = REQUIRED_DATE.matcher(text).find();
        boolean hasTotal = REQUIRED_TOTAL.matcher(text).find();
        if (hasDate && hasTotal) {
            return true;
        }
        return text.length() >= 250 && (hasDate || hasTotal);
    }

    private List<BufferedImage> preprocessImageFast(BufferedImage originalImage) {
        List<BufferedImage> variants = new ArrayList<>();
        variants.add(originalImage);
        try {
            Mat mat = preprocessingService.bufferedImageToMat(originalImage);
            Mat lab = preprocessingService.labColorSpacePipeline(mat);
            variants.add(preprocessingService.matToBufferedImage(lab));
        } catch (Exception e) {
            log.debug("Fallback fast preprocess limite a l'original: {}", e.getMessage());
        }
        return variants;
    }

    private OcrResult pickBestResult(OcrResult first, OcrResult second) {
        if (!isUsable(first)) {
            return second;
        }
        if (!isUsable(second)) {
            return first;
        }
        double firstScore = first.getConfidence() * 0.7 + first.getText().length() * 0.3;
        double secondScore = second.getConfidence() * 0.7 + second.getText().length() * 0.3;
        return firstScore >= secondScore ? first : second;
    }

    private boolean isUsable(OcrResult result) {
        return result != null && result.getText() != null && !result.getText().isBlank();
    }

    private boolean hasRequiredFields(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hasDate = REQUIRED_DATE.matcher(text).find();
        boolean hasTotal = REQUIRED_TOTAL.matcher(text).find();
        boolean hasIceOrIf = REQUIRED_ICE_IF.matcher(text).find();
        return hasDate && hasTotal && hasIceOrIf;
    }

    private OcrResult emptyZoneResult(BufferedImage zone) {
        return OcrResult.builder()
                .text("")
                .confidence(0.0)
                .attemptNumber(0)
                .imageWidth(zone.getWidth())
                .imageHeight(zone.getHeight())
                .success(true)
                .processingTimeMs(0L)
                .build();
    }

    private BufferedImage enforceOcrInputCap(BufferedImage image) {
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * height;
        if (pixels <= MAX_IMAGE_PIXELS && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
            return image;
        }
        double scaleByPixels = Math.sqrt((double) MAX_IMAGE_PIXELS / (double) pixels);
        double scaleByDimension = Math.min((double) MAX_IMAGE_DIMENSION / width, (double) MAX_IMAGE_DIMENSION / height);
        double scale = Math.min(scaleByPixels, scaleByDimension);
        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage scaled = new BufferedImage(newWidth, newHeight,
                image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }

    private BufferedImage enforceOcrInputCapFast(BufferedImage image) {
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * height;
        if (pixels <= IMAGE_FAST_MAX_PIXELS && width <= IMAGE_FAST_MAX_DIMENSION
                && height <= IMAGE_FAST_MAX_DIMENSION) {
            return image;
        }
        double scaleByPixels = Math.sqrt((double) IMAGE_FAST_MAX_PIXELS / (double) pixels);
        double scaleByDimension = Math.min((double) IMAGE_FAST_MAX_DIMENSION / width,
                (double) IMAGE_FAST_MAX_DIMENSION / height);
        double scale = Math.min(scaleByPixels, scaleByDimension);
        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage scaled = new BufferedImage(newWidth, newHeight,
                image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }

    /**
     * MERGE ZONE RESULTS: Combine header, body, footer into single OcrResult
     */
    private OcrResult mergeZoneResults(OcrResult header, OcrResult body, OcrResult footer) {
        log.info("=== FUSION RÉSULTATS ZONES ===");

        // Combiner les textes avec marqueurs de zone
        StringBuilder combinedText = new StringBuilder();
        combinedText.append("[HEADER]\n");
        combinedText.append(header.getText() != null ? header.getText() : "");
        combinedText.append("\n\n[BODY]\n");
        combinedText.append(body.getText() != null ? body.getText() : "");
        combinedText.append("\n\n[FOOTER]\n");
        combinedText.append(footer.getText() != null ? footer.getText() : "");

        // Calculer confiance moyenne pondérée
        // Footer = 40% (plus important pour ICE/IF/RC)
        // Body = 35% (tableaux)
        // Header = 25% (supplier/invoice#)
        double weightedConfidence = (header.getConfidence() * 0.25) +
                (body.getConfidence() * 0.35) +
                (footer.getConfidence() * 0.40);

        // Temps total = somme des temps de zone
        long totalTime = header.getProcessingTimeMs() +
                body.getProcessingTimeMs() +
                footer.getProcessingTimeMs();

        log.info("Zones fusionnées: {} chars total, {}% confiance, {}ms",
                combinedText.length(), (int) weightedConfidence, totalTime);
        log.info("  - Header: {} chars, {}%", header.getText() != null ? header.getText().length() : 0,
                (int) header.getConfidence());
        log.info("  - Body: {} chars, {}%", body.getText() != null ? body.getText().length() : 0,
                (int) body.getConfidence());
        log.info("  - Footer: {} chars, {}%", footer.getText() != null ? footer.getText().length() : 0,
                (int) footer.getConfidence());

        return OcrResult.builder()
                .text(combinedText.toString())
                .confidence(weightedConfidence)
                .processingTimeMs(totalTime)
                .success(true)
                .build();
    }

    /**
     * LEGACY: Extrait la zone footer (15% inférieur de l'image)
     * 
     * @deprecated Use extractZone(image, ZoneType.FOOTER) instead
     */
    /**
     * Enrichit le texte natif d'un PDF avec OCR sur premiÃ¨re/derniÃ¨re page.
     * Cela rÃ©cupÃ¨re les footers souvent absents du layer texte.
     */
    private boolean shouldEnrichPdfFooter(String nativeText) {
        if (nativeText == null || nativeText.isBlank()) {
            return false;
        }
        String upper = (" " + nativeText.toUpperCase() + " ");
        for (String keyword : FOOTER_KEYWORDS) {
            if (upper.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Enrichit le texte natif d'un PDF avec OCR cible sur le footer de la derniere
     * page.
     * On ajoute uniquement des lignes contenant des mots-cles footer.
     */
    private String mergeNativePdfWithOcr(org.apache.pdfbox.pdmodel.PDDocument document, String nativeText) {
        if (nativeText == null) {
            nativeText = "";
        }

        try {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                return nativeText;
            }

            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            Set<String> mergedLines = new LinkedHashSet<>();
            appendNonBlankLines(mergedLines, nativeText);

            int lastPage = pageCount - 1;
            BufferedImage pageImage = renderer.renderImageWithDPI(lastPage, 220);
            int footerStartY = (int) (pageImage.getHeight() * 0.55);
            int footerHeight = Math.max(1, pageImage.getHeight() - footerStartY);
            BufferedImage footerImage = pageImage.getSubimage(0, footerStartY, pageImage.getWidth(), footerHeight);

            String footerText = extractFooterTextFastVariants(footerImage);
            appendFooterKeywordLines(mergedLines, footerText);

            return String.join("\n", mergedLines).trim();
        } catch (Exception e) {
            log.warn("Enrichissement OCR PDF ignore (erreur): {}", e.getMessage());
            return nativeText;
        }
    }

    private void appendNonBlankLines(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String cleaned = line.trim();
            if (!cleaned.isBlank()) {
                target.add(cleaned);
            }
        }
    }

    private void appendFooterKeywordLines(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String cleaned = line.trim();
            if (cleaned.isBlank()) {
                continue;
            }

            String upper = (" " + cleaned.toUpperCase() + " ");
            boolean hasKeyword = false;
            for (String keyword : FOOTER_KEYWORDS) {
                if (upper.contains(keyword)) {
                    hasKeyword = true;
                    break;
                }
            }
            if (hasKeyword || hasStrongNumericSignal(cleaned)) {
                target.add(cleaned);
            }
        }
    }

    private boolean hasStrongNumericSignal(String line) {
        String digitsOnly = line.replaceAll("\\D", "");
        return digitsOnly.length() >= 11;
    }

    private String extractFooterTextFastVariants(BufferedImage footerImage) {
        StringBuilder all = new StringBuilder();

        OcrRuntimeContext localContext = new OcrRuntimeContext(2, 5_000L, false);
        OcrResult simple = performOcrWithConfig(footerImage, 0, localContext, "pdf_footer_enrich");
        if (simple != null && simple.getText() != null && !simple.getText().isBlank()) {
            all.append(simple.getText()).append('\n');
        }

        try {
            Mat footerMat = preprocessingService.bufferedImageToMat(footerImage);
            Mat lab = preprocessingService.labColorSpacePipeline(footerMat);
            BufferedImage labImage = preprocessingService.matToBufferedImage(lab);
            OcrResult labResult = performOcrWithConfig(labImage, 1, localContext, "pdf_footer_enrich");
            if (labResult != null && labResult.getText() != null && !labResult.getText().isBlank()) {
                all.append(labResult.getText());
            }
        } catch (Exception e) {
            log.debug("Variant LAB footer ignorée: {}", e.getMessage());
        }

        return all.toString();
    }

    private void enrichTelemetry(
            OcrResult result,
            OcrRuntimeContext context,
            long fileSizeBytes,
            int preWidth,
            int preHeight,
            int postWidth,
            int postHeight,
            String sourceName) {
        if (result == null) {
            return;
        }
        if (result.getTelemetry() == null) {
            result.setTelemetry(new LinkedHashMap<>());
        }
        Map<String, Object> telemetry = result.getTelemetry();
        telemetry.putIfAbsent("fileSizeBytes", fileSizeBytes);
        telemetry.put("imageWidthBeforePreprocess", preWidth);
        telemetry.put("imageHeightBeforePreprocess", preHeight);
        telemetry.put("imageWidthAfterPreprocess", postWidth);
        telemetry.put("imageHeightAfterPreprocess", postHeight);
        telemetry.put("ocrCallsCount", context.getOcrCalls());
        telemetry.put("ocrCallTimingsMs", new ArrayList<>(context.getCallDurationsMs()));
        telemetry.put("totalOcrTimeMs", context.getTotalOcrTimeMs());
        telemetry.put("fallbackTriggered", context.isFallbackTriggered());
        telemetry.putIfAbsent("source", sourceName);

        log.info(
                "OCR telemetry: fileSizeBytes={}, pageCount={}, before={}x{}, after={}x{}, calls={}, totalOcrTimeMs={}, fallbackTriggered={}",
                telemetry.get("fileSizeBytes"),
                telemetry.getOrDefault("pageCount", 1),
                preWidth,
                preHeight,
                postWidth,
                postHeight,
                context.getOcrCalls(),
                context.getTotalOcrTimeMs(),
                context.isFallbackTriggered());
    }

    private static final class OcrRuntimeContext {
        private final int maxCalls;
        private final long maxTotalTimeMs;
        private final boolean imageFastMode;
        private int ocrCalls;
        private long totalOcrTimeMs;
        private boolean fallbackTriggered;
        private final List<Long> callDurationsMs = new ArrayList<>();

        private OcrRuntimeContext(int maxCalls, long maxTotalTimeMs, boolean imageFastMode) {
            this.maxCalls = maxCalls;
            this.maxTotalTimeMs = maxTotalTimeMs;
            this.imageFastMode = imageFastMode;
        }

        private boolean canRunAnotherCall() {
            return ocrCalls < maxCalls && totalOcrTimeMs < maxTotalTimeMs;
        }

        private void recordCall(long durationMs, String stage, int attempt, int width, int height) {
            ocrCalls++;
            totalOcrTimeMs += Math.max(0L, durationMs);
            callDurationsMs.add(Math.max(0L, durationMs));
            log.info("OCR call #{}/{} [{} attempt={} {}x{}] took {} ms (total={} ms)",
                    ocrCalls, maxCalls, stage, attempt, width, height, durationMs, totalOcrTimeMs);
        }

        private int getOcrCalls() {
            return ocrCalls;
        }

        private long getTotalOcrTimeMs() {
            return totalOcrTimeMs;
        }

        private List<Long> getCallDurationsMs() {
            return callDurationsMs;
        }

        private boolean isFallbackTriggered() {
            return fallbackTriggered;
        }

        private void setFallbackTriggered(boolean fallbackTriggered) {
            this.fallbackTriggered = fallbackTriggered;
        }

        private boolean isImageFastMode() {
            return imageFastMode;
        }
    }

    @Deprecated
    private BufferedImage cropFooterZone(BufferedImage image) {
        int height = image.getHeight();
        int footerHeight = (int) (height * 0.15); // Derniers 15%
        int startY = height - footerHeight;

        log.debug("Crop footer: {}x{} → zone {}x{} (y={} à {})",
                image.getWidth(), height,
                image.getWidth(), footerHeight,
                startY, height);

        return image.getSubimage(0, startY, image.getWidth(), footerHeight);
    }

}
