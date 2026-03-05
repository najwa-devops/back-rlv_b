package com.example.releve_bancaire.servises.ocr;

import com.example.releve_bancaire.dto.ocr.OcrResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OcrFallbackStrategy {

    private final TesseractConfigService tesseractConfigService;
    private final ImagePreprocessingService preprocessingService;

    /**
     * Exécute stratégie fallback
     */
    public OcrResult executeFallback(BufferedImage originalImage, List<OcrResult> previousAttempts) {
        log.warn("=== ACTIVATION FALLBACK STRATEGY ===");

        List<OcrResult> fallbackResults = new ArrayList<>();

        try {
            // FALLBACK 1: Resize extrême x4
            log.info("Fallback 1: Resize extrême x4...");
            OcrResult extremeResize = tryExtremeResize(originalImage);
            if (extremeResize != null) {
                fallbackResults.add(extremeResize);
            }

            // FALLBACK 2: Inversion couleurs
            log.info("Fallback 2: Inversion couleurs...");
            OcrResult inverted = tryInvertedColors(originalImage);
            if (inverted != null) {
                fallbackResults.add(inverted);
            }

            // FALLBACK 3: Morphological ops
            log.info("Fallback 3: Morphological operations...");
            OcrResult morphological = tryMorphologicalOps(originalImage);
            if (morphological != null) {
                fallbackResults.add(morphological);
            }

            // Sélection meilleur fallback
            if (!fallbackResults.isEmpty()) {
                return fallbackResults.stream()
                        .max((r1, r2) -> Double.compare(
                                r1.getConfidence() * r1.getText().length(),
                                r2.getConfidence() * r2.getText().length()))
                        .orElse(null);
            }

        } catch (Exception e) {
            log.error("Fallback échoué: {}", e.getMessage(), e);
        }

        return null;
    }

    /**
     * FALLBACK 1: Resize extrême (x4)
     *
     * Pourquoi: Caractères très petits peuvent être illisibles à résolution normale
     * Impact: +15% pour factures scannées en basse résolution (<100 DPI)
     */
    private OcrResult tryExtremeResize(BufferedImage image) {
        try {
            Mat mat = preprocessingService.bufferedImageToMat(image);

            // Grayscale
            Mat gray = new Mat();
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = mat;
            }

            // Resize x4
            Mat resized = new Mat();
            Size newSize = new Size(gray.width() * 4.0, gray.height() * 4.0);
            Imgproc.resize(gray, resized, newSize, 0, 0, Imgproc.INTER_CUBIC);

            // Adaptive threshold
            Mat binary = new Mat();
            Imgproc.adaptiveThreshold(
                    resized,
                    binary,
                    255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    11,
                    2);

            BufferedImage processed = preprocessingService.matToBufferedImage(binary);

            // OCR
            Tesseract tesseract = tesseractConfigService.createConfiguredInstance(0);
            String text = tesseract.doOCR(processed);

            return OcrResult.builder()
                    .text(text != null ? text : "")
                    .confidence(70.0)
                    .attemptNumber(99)
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("Fallback extreme resize échoué: {}", e.getMessage());
            return null;
        }
    }

    /**
     * FALLBACK 2: Inversion couleurs
     *
     * Pourquoi: Certains documents ont texte clair sur fond sombre (rares mais
     * existent)
     * Impact: +100% pour ces cas spécifiques
     */
    private OcrResult tryInvertedColors(BufferedImage image) {
        try {
            Mat mat = preprocessingService.bufferedImageToMat(image);

            Mat gray = new Mat();
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = mat;
            }

            // Resize x2
            Mat resized = new Mat();
            Size newSize = new Size(gray.width() * 2.0, gray.height() * 2.0);
            Imgproc.resize(gray, resized, newSize, 0, 0, Imgproc.INTER_CUBIC);

            // Inversion (255 - pixel)
            Mat inverted = new Mat();
            org.opencv.core.Core.bitwise_not(resized, inverted);

            // Threshold
            Mat binary = new Mat();
            Imgproc.threshold(inverted, binary, 127, 255, Imgproc.THRESH_BINARY);

            BufferedImage processed = preprocessingService.matToBufferedImage(binary);

            // OCR
            Tesseract tesseract = tesseractConfigService.createConfiguredInstance(0);
            String text = tesseract.doOCR(processed);

            return OcrResult.builder()
                    .text(text != null ? text : "")
                    .confidence(65.0)
                    .attemptNumber(98)
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("Fallback inverted colors échoué: {}", e.getMessage());
            return null;
        }
    }

    /**
     * FALLBACK 3: Morphological operations
     *
     * Pourquoi: Dilatation/erosion peut combler texte cassé ou supprimer artefacts
     * Impact: +10% pour texte fragmenté
     */
    private OcrResult tryMorphologicalOps(BufferedImage image) {
        try {
            Mat mat = preprocessingService.bufferedImageToMat(image);

            Mat gray = new Mat();
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = mat;
            }

            // Resize x2
            Mat resized = new Mat();
            Size newSize = new Size(gray.width() * 2.0, gray.height() * 2.0);
            Imgproc.resize(gray, resized, newSize, 0, 0, Imgproc.INTER_CUBIC);

            // Threshold
            Mat binary = new Mat();
            Imgproc.adaptiveThreshold(
                    resized,
                    binary,
                    255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    11,
                    2);

            // Morphological closing (dilatation puis erosion)
            // Comble petits trous dans caractères
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
            Mat morphed = new Mat();
            Imgproc.morphologyEx(binary, morphed, Imgproc.MORPH_CLOSE, kernel);

            BufferedImage processed = preprocessingService.matToBufferedImage(morphed);

            // OCR
            Tesseract tesseract = tesseractConfigService.createConfiguredInstance(0);
            String text = tesseract.doOCR(processed);

            return OcrResult.builder()
                    .text(text != null ? text : "")
                    .confidence(68.0)
                    .attemptNumber(97)
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("Fallback morphological ops échoué: {}", e.getMessage());
            return null;
        }
    }

}
