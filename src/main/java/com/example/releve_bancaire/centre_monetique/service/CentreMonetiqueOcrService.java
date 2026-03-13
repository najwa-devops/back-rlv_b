package com.example.releve_bancaire.centre_monetique.service;

import lombok.RequiredArgsConstructor;
import net.sourceforge.tess4j.ITesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

@Service
@RequiredArgsConstructor
public class CentreMonetiqueOcrService {

    private static final int PDF_DPI = 300;
    private final ITesseract tesseract;

    public String extractText(byte[] fileData, String filename) throws Exception {
        if (fileData == null || fileData.length == 0) {
            throw new IllegalArgumentException("Fichier vide");
        }

        String name = filename == null ? "document" : filename.toLowerCase();

        if (name.endsWith(".pdf")) {
            return extractPdf(fileData);
        }
        if (isImageName(name)) {
            return extractImage(fileData);
        }

        throw new UnsupportedOperationException("Format non supporte: " + filename);
    }

    private String extractPdf(byte[] fileData) throws Exception {
        try (PDDocument document = PDDocument.load(fileData)) {
            if (isTextPdf(document)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(document);
            }

            StringBuilder out = new StringBuilder();
            PDFRenderer renderer = new PDFRenderer(document);
            tesseract.setPageSegMode(1);
            tesseract.setVariable("preserve_interword_spaces", "1");

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, PDF_DPI);
                BufferedImage preprocessed = preprocess(image);
                String pageText = tesseract.doOCR(preprocessed);
                if (pageText != null && !pageText.isBlank()) {
                    out.append(pageText).append("\n");
                }
            }
            return out.toString();
        }
    }

    private String extractImage(byte[] fileData) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileData));
        if (image == null) {
            throw new IllegalArgumentException("Image non lisible");
        }

        tesseract.setPageSegMode(1);
        tesseract.setVariable("preserve_interword_spaces", "1");

        return tesseract.doOCR(preprocess(image));
    }

    private boolean isTextPdf(PDDocument document) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(2, document.getNumberOfPages()));
        String text = stripper.getText(document);
        return text != null && text.trim().length() > 50;
    }

    private boolean isImageName(String filename) {
        return filename.endsWith(".png") || filename.endsWith(".jpg") || filename.endsWith(".jpeg")
                || filename.endsWith(".webp") || filename.endsWith(".bmp")
                || filename.endsWith(".tif") || filename.endsWith(".tiff");
    }

    private BufferedImage preprocess(BufferedImage source) {
        int w = Math.max(source.getWidth(), 1);
        int h = Math.max(source.getHeight(), 1);

        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, null);
        g.dispose();

        return gray;
    }
}
