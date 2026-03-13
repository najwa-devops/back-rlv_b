package com.example.releve_bancaire.centre_monetique.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CentreMonetiqueUploadResponseDTO {
    private String message;
    private CentreMonetiqueBatchDetailDTO batch;
    private List<CentreMonetiqueExtractionRow> rows;
}
