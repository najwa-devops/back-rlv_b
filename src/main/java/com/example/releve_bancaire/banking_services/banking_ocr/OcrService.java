package com.example.releve_bancaire.banking_services.banking_ocr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.*;

/**
 * ✅ SERVICE OCR - Extraction texte PDF et Excel
 *
 * CRITIQUE: Utilise -layout pour préserver structure colonnes
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService {

    private final OcrPreProcessor preProcessor;
    private final net.sourceforge.tess4j.ITesseract tesseract;

    private static final int DPI = 300; // Résolution standard pour OCR

    public String extractText(File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Fichier invalide");
        }

        String filename = file.getName().toLowerCase();

        try {
            if (filename.endsWith(".pdf")) {
                return extractFromPdfProfessional(file);
            } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                return extractFromExcel(file);
            } else if (filename.endsWith(".png") || filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
                return extractFromImage(file);
            } else {
                throw new UnsupportedOperationException("Format non supporté: " + filename);
            }
        } catch (Exception e) {
            log.error("Erreur extraction OCR: {}", e.getMessage(), e);
            throw new RuntimeException("Échec extraction: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ PIPELINE PROFESSIONNEL - Multi-pages, Preprocessing, OCR
     */
    private String extractFromPdfProfessional(File pdfFile) throws Exception {
        log.info("🚀 Pipeline OCR professionnel pour: {}", pdfFile.getName());
        StringBuilder finalOutput = new StringBuilder();

        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            log.info("Traitement de {} pages...", pageCount);

            for (int i = 0; i < pageCount; i++) {
                log.info("Traitement page {}/{}...", i + 1, pageCount);

                // 1. Conversion page en image
                BufferedImage image = renderer.renderImageWithDPI(i, DPI);

                // 2. Prétraitement OpenCV
                BufferedImage preprocessedImage = preProcessor.preprocess(image);

                // 3. OCR Tesseract
                String pageText = tesseract.doOCR(preprocessedImage);

                // 4. Fusion
                if (pageText != null) {
                    finalOutput.append("--- PAGE ").append(i + 1).append(" ---\n");
                    finalOutput.append(pageText).append("\n");
                }
            }
        }

        log.info("✅ OCR terminé: {} caractères", finalOutput.length());
        return finalOutput.toString();
    }

    private String extractFromImage(File imageFile) throws Exception {
        log.info("🖼️ OCR sur image: {}", imageFile.getName());
        BufferedImage image = javax.imageio.ImageIO.read(imageFile);
        BufferedImage preprocessed = preProcessor.preprocess(image);
        return tesseract.doOCR(preprocessed);
    }

    /**
     * ✅ EXTRACTION EXCEL
     */
    private String extractFromExcel(File excelFile) throws IOException {
        log.info("📊 Extraction Excel: {}", excelFile.getName());

        StringBuilder content = new StringBuilder();

        try (Workbook workbook = WorkbookFactory.create(excelFile)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                content.append("=== ").append(sheet.getSheetName()).append(" ===\n");

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

        log.info("✅ Excel: {} caractères", content.length());
        return content.toString();
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
                    double numValue = cell.getNumericCellValue();
                    if (numValue == (long) numValue) {
                        return String.valueOf((long) numValue);
                    }
                    return String.valueOf(numValue);
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            case BLANK:
                return "";
            default:
                return "";
        }
    }
}