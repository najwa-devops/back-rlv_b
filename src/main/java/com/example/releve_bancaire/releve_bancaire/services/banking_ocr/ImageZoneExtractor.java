package com.example.releve_bancaire.releve_bancaire.services.banking_ocr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

@Service
@Slf4j
@RequiredArgsConstructor // Generates constructor for final fields
public class ImageZoneExtractor {

    private final ITesseract tesseract; // Inject the configured bean from TesseractConfig

    private static final int MIN_WIDTH = 50;
    private static final int MIN_HEIGHT = 50;

    public String extractTextFromZone(
            String filePath,
            double xPercent,
            double yPercent,
            double widthPercent,
            double heightPercent) throws Exception {

        File file = new File(filePath);

        if (!file.exists()) {
            throw new IOException("Fichier introuvable : " + filePath);
        }

        String fileName = file.getName().toLowerCase();
        BufferedImage fullImage;

        if (fileName.endsWith(".pdf")) {
            log.info("Conversion PDF en image : {}", fileName);
            fullImage = convertPdfToImage(file);
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                fileName.endsWith(".png")) {
            log.info("Chargement image : {}", fileName);
            fullImage = ImageIO.read(file);
        } else {
            throw new IllegalArgumentException("Type de fichier non supporté (IMAGE ZONE) : " + fileName);
        }

        if (fullImage == null) {
            throw new IOException("Impossible de charger l'image depuis : " + filePath);
        }

        log.info("Découpage zone : x={}%, y={}%, w={}%, h={}%",
                xPercent, yPercent, widthPercent, heightPercent);

        BufferedImage croppedImage = cropImage(
                fullImage,
                xPercent,
                yPercent,
                widthPercent,
                heightPercent);

        if (croppedImage.getWidth() < MIN_WIDTH || croppedImage.getHeight() < MIN_HEIGHT) {
            log.warn("Image découpée trop petite ({}x{}), agrandissement à {}x{}",
                    croppedImage.getWidth(), croppedImage.getHeight(),
                    Math.max(croppedImage.getWidth(), MIN_WIDTH),
                    Math.max(croppedImage.getHeight(), MIN_HEIGHT));

            croppedImage = resizeImage(croppedImage,
                    Math.max(croppedImage.getWidth() * 3, MIN_WIDTH),
                    Math.max(croppedImage.getHeight() * 3, MIN_HEIGHT));
        }

        String extractedText = performOcr(croppedImage);

        log.info("Texte extrait de la zone : '{}'", extractedText);

        return extractedText.trim();
    }

    private BufferedImage convertPdfToImage(File pdfFile) throws IOException {
        PDDocument document = null;
        try {
            document = PDDocument.load(pdfFile);

            if (document.getNumberOfPages() == 0) {
                throw new IOException("Le PDF ne contient aucune page");
            }

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 300);

            log.info("PDF converti en image : {}x{} pixels",
                    image.getWidth(), image.getHeight());

            return image;

        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    log.warn("Erreur lors de la fermeture du PDF", e);
                }
            }
        }
    }

    private BufferedImage cropImage(
            BufferedImage fullImage,
            double xPercent,
            double yPercent,
            double widthPercent,
            double heightPercent) {
        int imageWidth = fullImage.getWidth();
        int imageHeight = fullImage.getHeight();

        int x = (int) ((xPercent / 100.0) * imageWidth);
        int y = (int) ((yPercent / 100.0) * imageHeight);
        int width = (int) ((widthPercent / 100.0) * imageWidth);
        int height = (int) ((heightPercent / 100.0) * imageHeight);

        x = Math.max(0, Math.min(x, imageWidth - 1));
        y = Math.max(0, Math.min(y, imageHeight - 1));
        width = Math.min(width, imageWidth - x);
        height = Math.min(height, imageHeight - y);

        log.debug("Découpage : x={}, y={}, w={}, h={} (image: {}x{})",
                x, y, width, height, imageWidth, imageHeight);

        return fullImage.getSubimage(x, y, width, height);
    }

    private BufferedImage resizeImage(BufferedImage original, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();

        // Utiliser un algorithme de haute qualité
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        return resized;
    }

    private String performOcr(BufferedImage image) throws Exception {
        // Use the injected, pre-configured Tesseract instance
        // No need to create a new one or set datapath/language again

        // Override page segmentation mode for zone extraction
        // (Your bean uses mode 3, but zone extraction works better with mode 6)
        tesseract.setPageSegMode(6); // Assume uniform block of text

        String text = tesseract.doOCR(image);

        // Reset back to default if needed (optional, depends on your use case)
        tesseract.setPageSegMode(3);

        return text != null ? text : "";
    }
}
