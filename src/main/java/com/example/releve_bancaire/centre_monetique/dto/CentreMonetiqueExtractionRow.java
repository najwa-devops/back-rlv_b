package com.example.releve_bancaire.centre_monetique.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CentreMonetiqueExtractionRow {
    private String section;
    private String date;
    private String reference;
    private String montant;
    private String debit;
    private String credit;
    private String dc;
}
