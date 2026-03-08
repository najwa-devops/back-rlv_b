package com.example.releve_bancaire.dto.ocr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrResult {

    private String text;
    private double confidence;
    private int attemptNumber;
    private int imageWidth;
    private int imageHeight;
    private boolean success;
    private String errorMessage;
    private long processingTimeMs;
    @Builder.Default
    private Map<String, Object> telemetry = new LinkedHashMap<>();

    public static OcrResult failed(String errorMessage) {
        return OcrResult.builder()
                .text("")
                .confidence(0.0)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

}
