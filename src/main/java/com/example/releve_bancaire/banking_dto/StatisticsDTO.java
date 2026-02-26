package com.example.releve_bancaire.banking_dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO pour les statistiques globales
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsDTO {

    private long total;
    private long pending;
    private long processing;
    private long treated;
    private long readyToValidate;
    private long validated;
    private long error;

    private long totalRibs;
    private long invalid;

    private Double averageConfidence;
}
