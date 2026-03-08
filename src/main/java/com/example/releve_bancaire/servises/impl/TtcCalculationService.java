package com.example.releve_bancaire.servises.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class TtcCalculationService {

    public Map<String, Object> calculateAndValidateTTC(
            Double amountHT,
            Double tva,
            Double ttcExtracted) {
        Map<String, Object> result = new HashMap<>();

        if (amountHT == null || tva == null) {
            result.put("error", "HT ou TVA manquant pour calcul TTC");
            result.put("canCalculate", false);
            return result;
        }

        // Calcul TTC
        Double ttcCalculated = amountHT + tva;
        result.put("ttcCalculated", round(ttcCalculated, 2));
        result.put("canCalculate", true);

        // Comparaison avec TTC extrait
        if (ttcExtracted != null) {
            double difference = Math.abs(ttcExtracted - ttcCalculated);
            result.put("ttcExtracted", round(ttcExtracted, 2));
            result.put("difference", round(difference, 2));

            // Alerte si différence > 0.01 DH
            if (difference > 0.01) {
                result.put("hasWarning", true);
                result.put("warningMessage", String.format(
                        "Le TTC extrait (%.2f DH) diffère du calcul automatique (%.2f DH) de %.2f DH",
                        ttcExtracted, ttcCalculated, difference));
                result.put("useCalculated", true); // Recommander le calcul
            } else {
                result.put("hasWarning", false);
            }
        } else {
            result.put("hasWarning", false);
            result.put("ttcExtracted", null);
        }

        return result;
    }

    private Double round(Double value, int decimals) {
        if (value == null)
            return null;
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }
}