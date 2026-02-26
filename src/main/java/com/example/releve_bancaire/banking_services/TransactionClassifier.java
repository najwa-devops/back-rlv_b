package com.example.releve_bancaire.banking_services;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

/**
 * Service de classification simple des transactions
 */
@Service
public class TransactionClassifier {

    @Data
    @Builder
    public static class ClassificationResult {
        private String categorie;
        private String role;
    }

    public ClassificationResult classify(String libelle, String sens) {
        String upperLibelle = libelle != null ? libelle.toUpperCase() : "";

        String categorie = "AUTRE";
        String role = "TRANSACTION";

        if (upperLibelle.contains("SALAIRE") || upperLibelle.contains("PAYE")) {
            categorie = "SALAIRE";
            role = "REVENU";
        } else if (upperLibelle.contains("LOYER")) {
            categorie = "LOGEMENT";
            role = "DEPENSE";
        } else if (upperLibelle.contains("RESTAURANT") || upperLibelle.contains("CAFE")) {
            categorie = "ALIMENTATION";
            role = "DEPENSE";
        } else if (upperLibelle.contains("VIREMENT")) {
            categorie = "VIREMENT";
            role = "TRANSFERT";
        }

        return ClassificationResult.builder()
                .categorie(categorie)
                .role(role)
                .build();
    }
}
