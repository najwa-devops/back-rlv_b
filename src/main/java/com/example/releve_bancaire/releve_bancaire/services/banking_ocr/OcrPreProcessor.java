package com.example.releve_bancaire.releve_bancaire.services.banking_ocr;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * Service de prétraitement d'images via OpenCV pour améliorer l'OCR.
 */
@Service
@Slf4j
public class OcrPreProcessor {

    @PostConstruct
    public void init() {
        try {
            OpenCV.loadShared();
            log.info("✅ OpenCV chargé avec succès");
        } catch (Exception e) {
            log.error("❌ Erreur lors du chargement d'OpenCV: {}", e.getMessage());
        }
    }

    /**
     * Applique un pipeline de prétraitement complet sur une image.
     */
    public BufferedImage preprocess(BufferedImage image) {
        if (image == null)
            return null;

        try {
            Mat mat = bufferedImageToMat(image);

            // 1. Grayscale
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);

            // 2. Denoising (subtle)
            Imgproc.GaussianBlur(mat, mat, new Size(3, 3), 0);

            // 3. Thresholding (Otsu)
            Imgproc.threshold(mat, mat, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

            return matToBufferedImage(mat);
        } catch (Exception e) {
            log.error("Erreur lors du prétraitement de l'image: {}", e.getMessage());
            return image; // Fallback à l'image originale
        }
    }

    /**
     * Convertit un BufferedImage en Mat OpenCV.
     */
    private Mat bufferedImageToMat(BufferedImage bi) {
        // S'assurer que l'image est en BGR
        BufferedImage converted = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        converted.getGraphics().drawImage(bi, 0, 0, null);

        byte[] data = ((DataBufferByte) converted.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(converted.getHeight(), converted.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, data);
        return mat;
    }

    /**
     * Convertit un Mat OpenCV en BufferedImage.
     */
    private BufferedImage matToBufferedImage(Mat mat) {
        int type = (mat.channels() > 1) ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_BYTE_GRAY;
        int bufferSize = mat.channels() * mat.cols() * mat.rows();
        byte[] b = new byte[bufferSize];
        mat.get(0, 0, b);
        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), type);
        final byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(b, 0, targetPixels, 0, b.length);
        return image;
    }
}
