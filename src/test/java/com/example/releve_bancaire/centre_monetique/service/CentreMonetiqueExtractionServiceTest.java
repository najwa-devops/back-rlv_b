package com.example.releve_bancaire.centre_monetique.service;

import com.example.releve_bancaire.centre_monetique.dto.CentreMonetiqueExtractionRow;
import com.example.releve_bancaire.centre_monetique.service.CentreMonetiqueExtractionService;
import com.example.releve_bancaire.centre_monetique.service.CentreMonetiqueStructureType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CentreMonetiqueExtractionServiceTest {

    private final CentreMonetiqueExtractionService service =
            new CentreMonetiqueExtractionService(null);

    @Test
    void shouldFillBaridReglementFieldsWithOcrDotSeparatedFormat() {
        String text = String.join("\n",
                "REGLEMENT: R001",
                "DATE REGLEMENT: 01/02/2026",
                "N COMPTE: 123456789012",
                "01/02/2026 12:00 1234********5678 VENTE 10.00 C 0.20 VISA",
                "Montant de reglement: 0.98 C Comm .H.T .0182 Comm. TVA .0018");

        CentreMonetiqueExtractionService.ExtractionPayload payload =
                service.extractFromTextWithSummary(text, 2026, CentreMonetiqueStructureType.BARID_BANK);

        CentreMonetiqueExtractionRow totalsRow = payload.rows().stream()
                .filter(row -> "REGLEMENT TOTALS".equalsIgnoreCase(row.getSection()))
                .findFirst()
                .orElse(null);

        assertNotNull(totalsRow);
        assertEquals("0.98", totalsRow.getMontant());
        assertEquals("0.02", totalsRow.getDebit());
        assertEquals("0", totalsRow.getCredit());
        assertEquals("C", totalsRow.getDc());
    }
}
