package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.banking_services.banking_ocr.OcrPreProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * ✅ STATEMENT PROCESSING ENGINE
 * Module indépendant pour le traitement des relevés bancaires (PDF Texte, PDF
 * Scan, Excel).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankStatementProcessor {

    private final OcrPreProcessor preProcessor;
    private final ITesseract tesseract;

    private static final int DPI = 300;

    /**
     * Point d'entrée principal pour l'extraction de texte d'un relevé.
     */
    public String process(File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Fichier invalide pour le traitement bancaire");
        }

        String filename = file.getName().toLowerCase();
        log.info("🚀 BankStatementProcessor: Traitement de {}", filename);

        try {
            if (filename.endsWith(".pdf")) {
                return processPdf(file);
            } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                return processExcel(file);
            } else if (isImage(filename)) {
                return processImage(file);
            } else {
                throw new UnsupportedOperationException("Format non supporté pour les relevés: " + filename);
            }
        } catch (Exception e) {
            log.error("❌ Erreur BankStatementProcessor: {}", e.getMessage(), e);
            throw new RuntimeException("Échec du traitement bancaire: " + e.getMessage(), e);
        }
    }

    public String process(byte[] fileData, String filename) {
        if (fileData == null || fileData.length == 0) {
            throw new IllegalArgumentException("Contenu de fichier vide");
        }

        String normalizedName = filename != null ? filename.toLowerCase() : "statement";
        log.info("🚀 BankStatementProcessor: Traitement en mémoire de {}", normalizedName);

        try {
            if (normalizedName.endsWith(".pdf")) {
                try (PDDocument document = PDDocument.load(fileData)) {
                    if (isTextPdf(document)) {
                        log.info("📄 PDF Texte détecté - Extraction directe (Sans OCR)");
                        return extractTextFromPdf(document);
                    }
                    log.info("🖼️ PDF Scanné détecté - Lancement du pipeline OCR multi-pages");
                    return extractTextViaOcr(document);
                }
            } else if (normalizedName.endsWith(".xlsx") || normalizedName.endsWith(".xls")) {
                return processExcel(fileData);
            } else if (isImage(normalizedName)) {
                return processImage(fileData);
            } else {
                throw new UnsupportedOperationException("Format non supporté pour les relevés: " + normalizedName);
            }
        } catch (Exception e) {
            log.error("❌ Erreur BankStatementProcessor (mémoire): {}", e.getMessage(), e);
            throw new RuntimeException("Échec du traitement bancaire: " + e.getMessage(), e);
        }
    }

    /**
     * Gère les PDF : Détecte si c'est du texte ou un scan.
     */
    private String processPdf(File pdfFile) throws Exception {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            if (isTextPdf(document)) {
                log.info("📄 PDF Texte détecté - Extraction directe (Sans OCR)");
                return extractTextFromPdf(document);
            } else {
                log.info("🖼️ PDF Scanné détecté - Lancement du pipeline OCR multi-pages");
                return extractTextViaOcr(document);
            }
        }
    }

    /**
     * Vérifie si le PDF contient du texte extractible.
     */
    private boolean isTextPdf(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(2, document.getNumberOfPages())); // Vérifier les 2 premières pages
        String text = stripper.getText(document);

        // Si on extrait plus de 50 caractères, on considère que c'est un PDF texte
        return text != null && text.trim().length() > 50;
    }

    /**
     * Extraction directe du texte via PDFBox.
     */
    private String extractTextFromPdf(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        int pageCount = document.getNumberOfPages();
        if (pageCount <= 1) {
            return stripper.getText(document);
        }

        StringBuilder output = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);
            String pageText = stripper.getText(document);
            if (pageText == null || pageText.isBlank()) {
                continue;
            }
            output.append("--- PAGE ").append(i + 1).append(" ---\n");
            output.append(pageText).append("\n");
        }
        return output.toString();
    }

    /**
     * Pipeline OCR multi-pages (Pipeline Professionnel).
     */
    private String extractTextViaOcr(PDDocument document) throws Exception {
        StringBuilder finalOutput = new StringBuilder();
        PDFRenderer renderer = new PDFRenderer(document);
        int pageCount = document.getNumberOfPages();

        // Appliquer configuration spécifique
        tesseract.setPageSegMode(1);
        tesseract.setVariable("preserve_interword_spaces", "1");

        for (int i = 0; i < pageCount; i++) {
            log.info("OCR Page {}/{}...", i + 1, pageCount);
            BufferedImage image = renderer.renderImageWithDPI(i, DPI);
            BufferedImage preprocessed = preProcessor.preprocess(image);
            String pageText = tesseract.doOCR(preprocessed);

            if (pageText != null) {
                finalOutput.append("--- PAGE ").append(i + 1).append(" ---\n");
                finalOutput.append(pageText).append("\n");
            }
        }
        return finalOutput.toString();
    }

    /**
     * Extraction Excel via Apache POI.
     */
    private String processExcel(File excelFile) throws IOException {
        log.info("📊 Extraction Excel directe");
        StringBuilder content = new StringBuilder();

        try (Workbook workbook = WorkbookFactory.create(excelFile)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                content.append("=== SHEET: ").append(sheet.getSheetName()).append(" ===\n");

                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String cellValue = getCellValueAsString(cell);
                        if (cellValue != null && !cellValue.trim().isEmpty()) {
                            content.append(cellValue).append("\t");
                        }
                    }
                    content.append("\n");
                }
            }
        }
        return content.toString();
    }

    private String processExcel(byte[] excelData) throws IOException {
        log.info("📊 Extraction Excel directe (mémoire)");
        StringBuilder content = new StringBuilder();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelData))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                content.append("=== SHEET: ").append(sheet.getSheetName()).append(" ===\n");

                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String cellValue = getCellValueAsString(cell);
                        if (cellValue != null && !cellValue.trim().isEmpty()) {
                            content.append(cellValue).append("\t");
                        }
                    }
                    content.append("\n");
                }
            }
        }
        return content.toString();
    }

    /**
     * Extraction depuis une image simple.
     */
    private String processImage(File imageFile) throws Exception {
        log.info("🖼️ OCR sur image simple");
        BufferedImage image = javax.imageio.ImageIO.read(imageFile);
        BufferedImage preprocessed = preProcessor.preprocess(image);

        tesseract.setPageSegMode(1);
        tesseract.setVariable("preserve_interword_spaces", "1");

        return tesseract.doOCR(preprocessed);
    }

    private String processImage(byte[] imageData) throws Exception {
        log.info("🖼️ OCR sur image simple (mémoire)");
        BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(imageData));
        BufferedImage preprocessed = preProcessor.preprocess(image);

        tesseract.setPageSegMode(1);
        tesseract.setVariable("preserve_interword_spaces", "1");

        return tesseract.doOCR(preprocessed);
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null)
            return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    double val = cell.getNumericCellValue();
                    return (val == (long) val) ? String.valueOf((long) val) : String.valueOf(val);
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    private boolean isImage(String filename) {
        return filename.endsWith(".png") || filename.endsWith(".jpg") || filename.endsWith(".jpeg");
    }
}
