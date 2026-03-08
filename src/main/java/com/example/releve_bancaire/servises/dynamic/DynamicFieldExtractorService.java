package com.example.releve_bancaire.servises.dynamic;

import com.example.releve_bancaire.entity.dynamic.DynamicTemplate;
import com.example.releve_bancaire.entity.dynamic.DynamicTemplate.DynamicFieldDefinitionJson;
import com.example.releve_bancaire.utils.ExtractionPatterns;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class DynamicFieldExtractorService {

    public DynamicExtractionResult extractWithTemplate(String ocrText, DynamicTemplate template) {
        long start = System.currentTimeMillis();

        Map<String, DynamicExtractionResult.ExtractedField> extracted = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> lowConfidence = new ArrayList<>();

        for (DynamicFieldDefinitionJson field : template.getFieldDefinitions()) {
            ExtractionAttempt attempt = extractField(ocrText, field);

            if (attempt.value == null || attempt.value.isBlank()) {
                if (Boolean.TRUE.equals(field.getRequired())) {
                    missing.add(field.getFieldName());
                }
                continue;
            }

            boolean lowConf = attempt.confidence < field.getConfidenceThreshold();
            if (lowConf) {
                lowConfidence.add(field.getFieldName());
            }

            extracted.put(field.getFieldName(),
                    DynamicExtractionResult.ExtractedField.builder()
                            .value(attempt.value)
                            .normalizedValue(attempt.value)
                            .confidence(attempt.confidence)
                            .detectionMethod(field.getDetectionMethod())
                            .validated(!lowConf)
                            .validationError(lowConf ? "Confidence faible" : null)
                            .build());
        }

        boolean complete = missing.isEmpty();
        double overallConfidence = extracted.isEmpty()
                ? 0.0
                : extracted.values().stream()
                        .mapToDouble(f -> f.getConfidence() != null ? f.getConfidence() : 0.0)
                        .average()
                        .orElse(0.0);

        return DynamicExtractionResult.builder()
                .templateId(template.getId())
                .templateName(template.getTemplateName())
                .extractedFields(extracted)
                .missingFields(missing)
                .lowConfidenceFields(lowConfidence)
                .overallConfidence(overallConfidence)
                .complete(complete)
                .extractionDurationMs(System.currentTimeMillis() - start)
                .build();
    }

    public DynamicExtractionResult extractWithoutTemplate(String ocrText) {
        long start = System.currentTimeMillis();

        log.info("Extraction sans template - utilisation des patterns par dÃƒÆ’Ã‚Â©faut");

        Map<String, DynamicExtractionResult.ExtractedField> extracted = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> lowConfidence = new ArrayList<>();

        // ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ CORRECTION: Utiliser les marqueurs de zones si disponibles
        String header = extractZoneText(ocrText, "HEADER");
        String body = extractZoneText(ocrText, "BODY");
        String footer = extractZoneText(ocrText, "FOOTER");

        // Fallback si pas de marqueurs
        if (header == null || header.isBlank()) {
            header = getHeader(ocrText); // Premier 30%
        }
        if (footer == null || footer.isBlank()) {
            footer = getFooter(ocrText); // Dernier 50%
        }
        if (body == null || body.isBlank()) {
            body = ocrText; // Tout le texte comme fallback
        }

        log.debug("Zones extraites - Header: {} chars, Body: {} chars, Footer: {} chars",
                header.length(), body.length(), footer.length());

        // ===================== EXTRACTION HEADER =====================

        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: NumÃƒÆ’Ã‚Â©ro de facture (patterns plus flexibles)
        extractAndAdd(extracted, missing, "invoiceNumber", header, Arrays.asList(
                // Pattern - "Reference / RÃ©fÃ©rence : F-202601-184"
                "(?im)^\\s*(?:R\\p{L}f\\p{L}rence|Reference|Ref\\.?|R[Ã©e]f\\p{L}rence)\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 0: "NumÃ©ro de facture : 105/2025"
                "(?im)^\\s*Num\\p{L}*\\s+de\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 1: "Facture 2026-FA050" (votre cas exact)
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s+(?:Avoir\\s+)?([0-9]{4}-[A-Z]{2}[0-9]+)\\b",

                // Pattern 2: "Facture NÃƒâ€šÃ‚Â° XXX" avec NÃƒâ€šÃ‚Â°
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s*(?:Avoir\\s+)?(?:N\\s*[Â°Âºo]?|No\\.?|#|:)\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 2b: "INV-CLI-0005" / "INV-2026-001"
                "\\b(INV(?:OICE)?-[A-Z0-9]{2,}(?:-[A-Z0-9]{2,})+)\\b",

                // Pattern 2c: "NÂ° de Facture: FA000132/26"
                "(?im)^\\s*N\\s*[^A-Za-z0-9]{0,3}\\s*de\\s*Facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 3: "FACTURE: XXX" avec deux-points
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s*(?:Avoir\\s+)?[:\\s]+([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 3b: "Codafiin Facture POS-26-00002"
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 3c: "( FACTURE NÂ° : 250182 )"
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?(?:N\\s*[Â°Âºo]?|No\\.?|#|:)\\s*([0-9]{3,})\\b",

                // Pattern 3d: "FACTURE Nâ–‘ : 250182" (OCR NÂ° variantes)
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?N\\s*[^A-Za-z0-9]{0,3}\\s*[:\\-]?\\s*([0-9]{3,})\\b",

                // Pattern 4: Format annÃƒÆ’Ã‚Â©e-lettres-chiffres (2026-FA050, 2024-INV001)
                "\\b([0-9]{4}-[A-Z]{2,}[0-9]+)\\b",

                // Pattern 5: GÃƒÆ’Ã‚Â©nÃƒÆ’Ã‚Â©rique aprÃƒÆ’Ã‚Â¨s "Facture"
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s+(?:Avoir\\s+)?([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 6: "Correction facture : IN2602-0001"
                "(?im)^\\s*Correction\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b"));

        if (!extracted.containsKey("invoiceNumber")) {
            ExtractionAttempt invoiceFallback = tryPatterns(ocrText, Arrays.asList(
                    "(?im)^\\s*(?:R\\p{L}f\\p{L}rence|Reference|Ref\\.?|R[Ã©e]f\\p{L}rence)\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*N\\s*[^A-Za-z0-9]{0,3}\\s*de\\s*Facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "\\b(INV(?:OICE)?-[A-Z0-9]{2,}(?:-[A-Z0-9]{2,})+)\\b",
                    "(?im)^\\s*(?:FACTURE|Facture|INVOICE|Invoice)\\s*(?:Avoir\\s+)?(?:N[Â°Âºo]|No\\.?|#|:)\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*Num\\p{L}*\\s+de\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*Correction\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b"));
            if (invoiceFallback.value != null && !invoiceFallback.value.isBlank()) {
                String normalized = normalizeValue("invoiceNumber", invoiceFallback.value);
                addExtractedField(extracted, "invoiceNumber", normalized, invoiceFallback.confidence);
                missing.remove("invoiceNumber");
            }
        }
        enforcePreferredInvoiceNumber(extracted, header + "\n" + ocrText);

        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: Date facture (cherche "Date facturation")
        extractAndAdd(extracted, missing, "invoiceDate", header, Arrays.asList(
                // Pattern 1: "Date facturation : 09/02/2026" (votre cas)
                "Date\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 2: "Date de facturation:"
                "Date\\s+de\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 3: "Date facture:"
                "Date\\s+(?:de\\s+)?facture\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 4: "Date:" suivi d'une date
                "Date\\s*[:'`.\\-\\s]+([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 5: Date avec sÃƒÂ©parateur obligatoire (ÃƒÂ©vite ICE)
                "([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})",
                "(?im)\\bLe\\s*[:\\-]?\\s*([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2,4})",
                "(?im)\\b([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2})\\b"));

        if (!extracted.containsKey("invoiceDate")) {
            ExtractionAttempt dateFallback = tryPatterns(ocrText, Arrays.asList(
                    "Date\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s+de\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s+(?:de\\s+)?facture\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s*[:'`.\\-\\s]+([0-9OIl\\s/\\-.]{8,16})",
                    "([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})",
                    "(?im)\\bLe\\s*[:\\-]?\\s*([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2,4})",
                    "(?im)\\b([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2})\\b"));
            if (dateFallback.value != null && !dateFallback.value.isBlank()) {
                String normalized = normalizeValue("invoiceDate", dateFallback.value);
                addExtractedField(extracted, "invoiceDate", normalized, dateFallback.confidence);
                missing.remove("invoiceDate");
            }
        }

        if (!extracted.containsKey("invoiceDate")) {
            String looseDate = extractDateFromLabeledContext(ocrText);
            if (looseDate != null) {
                addExtractedField(extracted, "invoiceDate", looseDate, 0.85);
                missing.remove("invoiceDate");
                log.info("Date extraite en mode tolerant: {}", looseDate);
            }
        }

        if (!extracted.containsKey("invoiceDate")) {
            String headerDate = extractDateFromHeaderHeuristic(header);
            if (headerDate != null) {
                addExtractedField(extracted, "invoiceDate", headerDate, 0.80);
                missing.remove("invoiceDate");
                log.info("Date extraite depuis en-tete (heuristique): {}", headerDate);
            }
        }

        // ===================== EXTRACTION FOURNISSEUR (TEXTE COMPLET)
        // =====================

        String sanitizedFooter = sanitizeTextForSupplierIdentifiers(footer);
        String sanitizedFullText = sanitizeTextForSupplierIdentifiers(ocrText);
        String sanitizedHeader = sanitizeTextForSupplierIdentifiers(header);

        // ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ NOUVEAU: Extraction unifiÃƒÆ’Ã‚Â©e (si les trois sont sur la
        // mÃƒÆ’Ã‚Âªme ligne/bloc)
        extractUnifiedIdentifiers(sanitizedFooter, extracted);

        // ICE du FOURNISSEUR (FOOTER UNIQUEMENT)
        if (!extracted.containsKey("ice")) {
            String ice = extractLastMatchStrict(sanitizedFooter,
                    Arrays.asList(ExtractionPatterns.ICE_PATTERNS));

            if (ice == null) {
                ice = extractLastMatchStrict(sanitizedHeader,
                        Arrays.asList(ExtractionPatterns.ICE_PATTERNS));
            }

            if (ice == null) {
                ice = extractLastMatchStrict(sanitizedFullText,
                        Arrays.asList(ExtractionPatterns.ICE_PATTERNS));
            }

            if (ice == null) {
                ice = extractIceLoose(sanitizedFooter);
            }
            if (ice == null) {
                ice = extractIceLoose(sanitizedHeader);
            }
            if (ice == null) {
                ice = extractIceLoose(sanitizedFullText);
            }
            if (ice == null) {
                ice = extractIceByProximity(sanitizedFooter);
            }
            if (ice == null) {
                ice = extractIceByProximity(sanitizedFullText);
            }
            if (ice == null) {
                ice = extractIceByProximity(footer);
            }
            if (ice == null) {
                ice = extractIceByProximity(ocrText);
            }

            if (ice != null) {
                ice = ice.replaceAll("\\s+", "");
                if (ice.matches("\\d{15}")) {
                    addExtractedField(extracted, "ice", ice, 0.95);
                    log.info("ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ ICE fournisseur extrait depuis FOOTER: {}", ice);
                } else {
                    log.warn("ICE invalide (longueur != 15): {}", ice);
                    missing.add("ice");
                }
            } else {
                log.warn("ÃƒÂ¢Ã‚ÂÃ…â€™ Aucun ICE trouvÃƒÆ’Ã‚Â© dans FOOTER");
                missing.add("ice");
            }
        }

        // IF du FOURNISSEUR (FOOTER UNIQUEMENT)
        if (!extracted.containsKey("ifNumber")) {
            String ifNumber = extractLastMatchStrict(sanitizedFooter,
                    Arrays.asList(ExtractionPatterns.IF_PATTERNS));

            if (ifNumber == null) {
                ifNumber = extractLastMatchStrict(sanitizedHeader,
                        Arrays.asList(ExtractionPatterns.IF_PATTERNS));
            }

            if (ifNumber == null) {
                ifNumber = extractLastMatchStrict(sanitizedFullText,
                        Arrays.asList(ExtractionPatterns.IF_PATTERNS));
            }

            if (ifNumber != null) {
                addExtractedField(extracted, "ifNumber", ifNumber, 0.95);
                log.info("IF fournisseur extrait: {}", ifNumber);
            } else {
                log.warn("Aucun IF trouvÃƒÆ’Ã‚Â© dans le footer");
                missing.add("ifNumber");
            }
        }

        // RC du FOURNISSEUR (FOOTER UNIQUEMENT)
        if (!extracted.containsKey("rcNumber")) {
            String rc = extractLastMatchStrict(sanitizedFooter,
                    Arrays.asList(ExtractionPatterns.RC_PATTERNS));

            if (rc == null) {
                rc = extractLastMatchStrict(sanitizedHeader,
                        Arrays.asList(ExtractionPatterns.RC_PATTERNS));
            }

            if (rc == null) {
                rc = extractLastMatchStrict(sanitizedFullText,
                        Arrays.asList(ExtractionPatterns.RC_PATTERNS));
            }

            if (rc != null) {
                addExtractedField(extracted, "rcNumber", rc, 0.95);
                log.info("RC fournisseur extrait depuis FOOTER: {}", rc);
            } else {
                log.warn("Aucun RC trouvÃƒÆ’Ã‚Â© dans FOOTER");
                missing.add("rcNumber");
            }
        }

        // SUPPLIER - Smart extraction using all zones
        String supplier = extractSupplierSmart(header, footer, ocrText);
        if (supplier != null) {
            addExtractedField(extracted, "supplier", supplier, 0.95);
            log.info("Supplier extrait (smart): {}", supplier);
        } else {
            missing.add("supplier");
        }

        // ===================== EXTRACTION MONTANTS =====================

        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: Montant HT (gÃƒÆ’Ã‚Â¨re virgule ET point) - Cherche
        // dans BODY ou OCR
        String totalsPriorityText = buildTotalsPriorityText(footer, body, ocrText);
        Map<String, String> totalsByLabel = extractTotalsByLabel(totalsPriorityText);
        List<String> allTvaValues = extractAllTvaValues(totalsPriorityText + "\n" + ocrText);
        List<String> allHtValues = extractAllHtValues(totalsPriorityText + "\n" + ocrText);
        addAmountFromLabeledTotals(extracted, missing, "amountHT", totalsByLabel);
        addAmountFromLabeledTotals(extracted, missing, "tva", totalsByLabel);
        addAmountFromLabeledTotals(extracted, missing, "amountTTC", totalsByLabel);

        if (!extracted.containsKey("amountHT")) {
            extractAmountWithFallback(extracted, missing, "amountHT", totalsPriorityText, ocrText, Arrays.asList(

                    // Pattern 1: Matches "Total HT" or "Total H.T." or "Total Hors Taxes"
                    // Examples:
                    // "Total HT 448,00"
                    // "Total H.T. : 448,00"
                    // "Total Hors Taxes - 1 234,56"
                    "(?i)Total\\s+(?:H\\.?T\\.?|Hors\\s+Taxe[s]?)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 2: Matches "Montant HT" or "Montant Hors Taxes"
                    // Example: "Montant HT: 448,00"
                    "(?i)Montant\\s+(?:H\\.?T\\.?|Hors\\s+Taxe[s]?)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 3: Matches "Sous-total HT"
                    // Example: "Sous-total HT 1 200,00"
                    "(?i)Sous[-\\s]?total\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 4: Matches "Net HT"
                    // Example: "Net HT: 980,00"
                    "(?i)Net\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 5: Matches "Base HT"
                    // Example: "Base HT 500,00"
                    "(?i)Base\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 6: Matches "Prix HT"
                    // Example: "Prix HT : 250,00"
                    "(?i)Prix\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 7: Matches "Valeur HT"
                    // Example: "Valeur HT 300,00"
                    "(?i)Valeur\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 8: Matches cases where "HT" appears directly before the amount
                    // Example: "HT 448,00"
                    "(?i)(?:HT|H\\.T\\.)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 9: Matches cases where the amount appears on the next line (common in
                    // OCR table extraction)
                    // Example:
                    // Total HT
                    // 1 234,56
                    "(?i)Total\\s+(?:H\\.?T\\.?|HT)[\\s\\n]+([\\d\\s]+[,.]\\d{2})",

                    // Pattern 10: "Sous-total : 35.00 MAD"
                    "(?i)Sous[-\\s]?total\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|â‚¬)?"));
        }

        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: TVA (gÃƒÆ’Ã‚Â¨re "Total TVA 20% 89,60")
        if (!extracted.containsKey("tva")) {
            extractAmountWithFallback(extracted, missing, "tva", totalsPriorityText, ocrText, Arrays.asList(
                    // Pattern 0: "TVA (20%) 1 106,00"
                    "(?i)T\\.?V\\.?A\\.?\\s*\\(?\\s*\\d{1,2}\\s*%\\s*\\)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 1: "Total TVA 20% 89,60" (votre cas exact)
                    "Total\\s+T\\.?V\\.?A\\.?\\s+(?:\\d{1,2}%)?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 2: "Total T.V.A. : 89,60"
                    "Total\\s*T\\.?V\\.?A\\.?\\s*(?:\\d{1,2}%)?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                    // Pattern 3: "TVA 20%: 89,60"
                    "T\\.?V\\.?A\\.?\\s*(?:\\d{1,2}%)?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                    // Pattern 4: Dans un tableau
                    "Total\\s*T\\.?V\\.?A\\.?[\\s\\n]+(?:\\d{1,2}%)?[\\s\\n]*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 5: Tv 20 %
                    "T\\.?V\\.?\\s*(?:\\d{1,2}\\s*%)?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                    // Pattern 6: "Total taxes : 7.00 MAD"
                    "(?i)Total\\s+tax(?:e|es|s)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|â‚¬)?"));
        }

        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: Montant TTC (gÃƒÆ’Ã‚Â¨re point dÃƒÆ’Ã‚Â©cimal
        // "537.60")
        if (!extracted.containsKey("amountTTC")) {
            extractAmountWithFallback(extracted, missing, "amountTTC", totalsPriorityText, ocrText, Arrays.asList(
                    // Pattern 0: "Montant NET TTC (MAD) 6 636,00"
                    "(?i)Montant\\s+NET\\s+T\\.?T\\.?C\\.?\\s*(?:\\(\\s*MAD\\s*\\))?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 1: "Total TTC 537.60" (votre cas - point dÃƒÆ’Ã‚Â©cimal)
                    "Total\\s+T\\.?T\\.?C\\.?\\s+([\\d\\s]+[,.]\\d{2})",

                    // Pattern 2: "Total T.T.C. : 537.60"
                    "Total\\s*T\\.?T\\.?C\\.?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                    // Pattern 3: "Net ÃƒÆ’Ã‚Â  payer: 537.60"
                    "(?iu)Net\\s+[aàâ]\\s+payer\\s+T\\.?T\\.?C\\.?\\s*[:\\-\\|]?\\s*([\\d\\s]+[,.]\\d{2})",
                    "(?i)Net\\s*[^\\n\\d]{0,30}T\\.?T\\.?C\\.?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                    "(?i)Net\\s*[aÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â ]\\s*payer\\s*T\\.?T\\.?C\\.?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                    "Net\\s*[ÃƒÆ’Ã‚Â a]\\s*payer\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                    "(?i)Net\\s*[^\\n\\d]{0,12}payer(?:\\s*T\\.?T\\.?C\\.?)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                    // Pattern 4: "Montant TTC:"
                    "Montant\\s*T\\.?T\\.?C\\.?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                    // Pattern 5: Dans un tableau
                    "Total\\s*T\\.?T\\.?C\\.?[\\s\\n]+([\\d\\s]+[,.]\\d{2})",

                    // Pattern 6: "TOTAL : 42.00 MAD"
                    "(?i)^\\s*TOTAL\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|â‚¬)?\\s*$"));
        }

        if (!allTvaValues.isEmpty()) {
            String tvaValuesDisplay = String.join(" | ", allTvaValues);
            extracted.put("tvaValues",
                    DynamicExtractionResult.ExtractedField.builder()
                            .value(tvaValuesDisplay)
                            .normalizedValue(tvaValuesDisplay)
                            .confidence(0.97)
                            .detectionMethod("MULTI_TVA_DETECTION")
                            .validated(true)
                            .build());
            log.info("TVA detectees: {}", tvaValuesDisplay);
        }

        if (!allHtValues.isEmpty()) {
            String htValuesDisplay = String.join(" | ", allHtValues);
            extracted.put("htValues",
                    DynamicExtractionResult.ExtractedField.builder()
                            .value(htValuesDisplay)
                            .normalizedValue(htValuesDisplay)
                            .confidence(0.97)
                            .detectionMethod("MULTI_HT_DETECTION")
                            .validated(true)
                            .build());
            log.info("HT detectes: {}", htValuesDisplay);
        }

        reconcileInvoiceAmounts(extracted, missing);

        // Calculer les mÃƒÆ’Ã‚Â©triques
        boolean complete = missing.isEmpty() || missing.size() <= 2;
        double overallConfidence = extracted.isEmpty()
                ? 0.0
                : extracted.values().stream()
                        .mapToDouble(f -> f.getConfidence() != null ? f.getConfidence() : 0.0)
                        .average()
                        .orElse(0.0);

        log.info("Extraction terminÃƒÆ’Ã‚Â©e: {} champs extraits, {} manquants, confiance {}%",
                extracted.size(), missing.size(), Math.round(overallConfidence * 100));

        return DynamicExtractionResult.builder()
                .templateId(null)
                .templateName("DEFAULT")
                .extractedFields(extracted)
                .missingFields(missing)
                .lowConfidenceFields(lowConfidence)
                .overallConfidence(overallConfidence)
                .complete(complete)
                .extractionDurationMs(System.currentTimeMillis() - start)
                .build();
    }

    // ===================== MeTHODES ZONES =====================
    private String getHeader(String text) {
        if (text == null || text.isEmpty())
            return "";
        int headerEnd = (int) (text.length() * 0.30);
        return text.substring(0, headerEnd);
    }

    private String getFooter(String text) {
        if (text == null || text.isEmpty())
            return "";
        int footerStart = (int) (text.length() * 0.50);
        return text.substring(footerStart);
    }

    // ===================== MeTHODES EXTRACTION =====================
    private void extractAndAdd(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing,
            String fieldName,
            String text,
            List<String> patterns) {
        ExtractionAttempt attempt = tryPatterns(text, patterns);

        if (attempt.value != null && !attempt.value.isBlank()) {
            String normalizedValue = normalizeValue(fieldName, attempt.value);
            if (normalizedValue == null || normalizedValue.isBlank()) {
                addMissingField(missing, fieldName);
                return;
            }
            addExtractedField(extracted, fieldName, normalizedValue, attempt.confidence);
        } else {
            addMissingField(missing, fieldName);
        }
    }

    private void extractAmountWithFallback(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing,
            String fieldName,
            String preferredText,
            String fallbackText,
            List<String> patterns) {
        ExtractionAttempt fromPreferred = tryPatternsBestAmount(preferredText, patterns, fieldName);
        ExtractionAttempt selected = fromPreferred;

        if (selected.value == null || selected.value.isBlank()) {
            selected = tryPatternsBestAmount(fallbackText, patterns, fieldName);
        }

        if (selected.value != null && !selected.value.isBlank()) {
            String normalizedValue = normalizeValue(fieldName, selected.value);
            if (parseAmount(normalizedValue) != null) {
                addExtractedField(extracted, fieldName, normalizedValue, selected.confidence);
                return;
            }
        }

        addMissingField(missing, fieldName);
    }

    private void addExtractedField(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String fieldName,
            String value,
            double confidence) {
        extracted.put(fieldName,
                DynamicExtractionResult.ExtractedField.builder()
                        .value(value)
                        .normalizedValue(value)
                        .confidence(confidence)
                        .detectionMethod("DEFAULT_PATTERN")
                        .validated(confidence >= 0.7)
                        .validationError(confidence < 0.7 ? "Confidence faible" : null)
                        .build());
    }

    private String extractLastMatch(String text, List<String> patterns) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String lastMatch = null;

        for (String patternStr : patterns) {
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);

                while (matcher.find()) {
                    String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();

                    if (value != null && !value.isBlank()) {
                        String normalized = value.replaceAll("\\s+", "");

                        if (!normalized.isEmpty()) {
                            lastMatch = normalized;
                            log.debug("Match trouvÃƒÆ’Ã‚Â© avec pattern '{}': {}", patternStr, lastMatch);
                        }
                    }
                }

                if (lastMatch != null) {
                    log.debug("Dernier match retenu: {} (pattern: {})", lastMatch, patternStr);
                    return lastMatch;
                }

            } catch (Exception e) {
                log.warn("Erreur avec le pattern '{}': {}", patternStr, e.getMessage());
            }
        }

        return null;
    }

    private String extractSupplierFromFooter(String footer) {
        Pattern siegePattern = Pattern.compile(
                "Si[eÃƒÆ’Ã‚Â¨]ge\\s*social\\s*[:\\s]*([A-Z][A-Za-z0-9\\s&.,''()-]{2,50}?)\\s*[-ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“]",
                Pattern.CASE_INSENSITIVE);
        Matcher siegeMatcher = siegePattern.matcher(footer);
        if (siegeMatcher.find()) {
            String supplier = siegeMatcher.group(1).trim();
            log.debug("Supplier trouvÃƒÆ’Ã‚Â© via 'SiÃƒÆ’Ã‚Â¨ge social': {}", supplier);
            return supplier;
        }

        Pattern proprietairePattern = Pattern.compile(
                "(?:Nom\\s*du\\s*propriÃƒÆ’Ã‚Â©taire|Titulaire)\\s*(?:du\\s*compte)?\\s*[:\\s]*([A-Z][A-Za-z0-9\\s&.,''()-]+(?:SARL|SAS|SA|S\\.A\\.R\\.L))",
                Pattern.CASE_INSENSITIVE);
        Matcher proprietaireMatcher = proprietairePattern.matcher(footer);
        if (proprietaireMatcher.find()) {
            String supplier = proprietaireMatcher.group(1).trim();
            log.debug("Supplier trouvÃƒÆ’Ã‚Â© via 'Nom du propriÃƒÆ’Ã‚Â©taire': {}", supplier);
            return supplier;
        }

        Pattern saPattern = Pattern.compile(
                "([A-Z][A-Z0-9\\s&.,''()-]{2,40}\\s+(?:SARL|SAS|SA|S\\.A\\.R\\.L|S\\.A))\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher saMatcher = saPattern.matcher(footer);
        String lastSaMatch = null;
        while (saMatcher.find()) {
            lastSaMatch = saMatcher.group(1).trim();
        }
        if (lastSaMatch != null) {
            log.debug("Supplier trouvÃƒÆ’Ã‚Â© via SARL/SA pattern: {}", lastSaMatch);
            return lastSaMatch;
        }

        return null;
    }

    private ExtractionAttempt tryPatterns(String text, List<String> patterns) {
        for (int i = 0; i < patterns.size(); i++) {
            String patternStr = patterns.get(i);
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                    double confidence = 0.9 - (i * 0.1);

                    log.debug("Pattern match trouvÃƒÆ’Ã‚Â©: '{}' ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ valeur: '{}'",
                            patternStr.substring(0, Math.min(50, patternStr.length())), value);

                    return new ExtractionAttempt(value.trim(), Math.max(confidence, 0.6));
                }
            } catch (Exception e) {
                log.warn("Pattern invalide: {}", patternStr);
            }
        }

        log.debug("Aucun pattern ne correspond");
        return new ExtractionAttempt(null, 0.0);
    }

    private ExtractionAttempt tryPatternsBestAmount(String text, List<String> patterns, String fieldName) {
        if (text == null || text.isBlank()) {
            return new ExtractionAttempt(null, 0.0);
        }

        String sanitizedText = sanitizeTextForAmountExtraction(text);
        AmountCandidate best = null;

        for (int i = 0; i < patterns.size(); i++) {
            String patternStr = patterns.get(i);
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(sanitizedText);
                while (matcher.find()) {
                    String rawValue = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                    if (rawValue == null || rawValue.isBlank()) {
                        continue;
                    }

                    String normalizedValue = normalizeValue(fieldName, rawValue);
                    Double numericValue = parseAmount(normalizedValue);
                    if (numericValue == null || numericValue < 0) {
                        continue;
                    }

                    double score = scoreAmountCandidate(sanitizedText, matcher.start(), matcher.end(), i, fieldName);
                    AmountCandidate candidate = new AmountCandidate(normalizedValue, numericValue, score);
                    if (best == null || candidate.score > best.score) {
                        best = candidate;
                    }
                }
            } catch (Exception e) {
                log.warn("Pattern invalide pour montant: {}", patternStr);
            }
        }

        if (best == null) {
            return new ExtractionAttempt(null, 0.0);
        }

        double confidence = Math.max(0.65, Math.min(0.99, best.score));
        return new ExtractionAttempt(best.normalizedValue, confidence);
    }

    private String sanitizeTextForAmountExtraction(String text) {
        if (text == null) {
            return "";
        }
        String sanitized = text
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ');

        sanitized = sanitized.replaceAll("(?<=\\d)[Oo](?=[\\d,\\.])", "0");
        sanitized = sanitized.replaceAll("(?<=[\\d,\\.])[Oo](?=\\d)", "0");
        sanitized = sanitized.replaceAll("(?<=\\d)[Il](?=\\d)", "1");
        sanitized = sanitized.replaceAll("(?<=\\d)S(?=\\d)", "5");

        return sanitized;
    }

    private double scoreAmountCandidate(String text, int start, int end, int patternIndex, String fieldName) {
        int contextStart = Math.max(0, start - 60);
        int contextEnd = Math.min(text.length(), end + 60);
        String context = normalizeTextForMatching(text.substring(contextStart, contextEnd));

        double score = 0.85 - (patternIndex * 0.03);

        if (context.contains("total")) {
            score += 0.10;
        }
        if (context.contains("montant")) {
            score += 0.05;
        }
        if (context.contains("net a payer") || context.contains("net payer")) {
            score += 0.10;
        }

        if ("amountHT".equals(fieldName) && (context.contains("ht") || context.contains("hors taxe"))) {
            score += 0.08;
        }
        if ("tva".equals(fieldName) && context.contains("tva")) {
            score += 0.10;
        }
        if ("amountTTC".equals(fieldName) && context.contains("ttc")) {
            score += 0.10;
        }
        if ("amountTTC".equals(fieldName) && (context.contains("net a payer") || context.contains("net payer"))) {
            score += 0.18;
        }
        if ("amountTTC".equals(fieldName) && !context.contains("ttc")
                && !context.contains("net a payer")
                && !context.contains("net payer")) {
            score -= 0.25;
        }
        if ("amountTTC".equals(fieldName) && !context.contains("ttc")
                && (context.contains("ht") || context.contains("tva"))) {
            score -= 0.12;
        }

        if (context.contains("prix unitaire") || context.contains("qte") || context.contains("quantite")
                || context.contains("remise")) {
            score -= 0.20;
        }

        if (!text.isEmpty()) {
            score += ((double) start / (double) text.length()) * 0.08;
        }

        return score;
    }

    private String buildTotalsPriorityText(String footer, String body, String fullText) {
        StringBuilder sb = new StringBuilder();
        if (footer != null && !footer.isBlank()) {
            sb.append(footer).append('\n');
        }
        if (body != null && !body.isBlank()) {
            sb.append(body).append('\n');
        }
        if (sb.length() == 0 && fullText != null) {
            sb.append(fullText);
        }
        return sb.toString();
    }

    private void addAmountFromLabeledTotals(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing,
            String fieldName,
            Map<String, String> totalsByLabel) {
        if (extracted.containsKey(fieldName)) {
            return;
        }
        String value = totalsByLabel.get(fieldName);
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = normalizeValue(fieldName, value);
        if (parseAmount(normalized) == null) {
            return;
        }
        addExtractedField(extracted, fieldName, normalized, 0.98);
        missing.remove(fieldName);
        log.info("Montant extrait via bloc totals [{}] = {}", fieldName, normalized);
    }

    private Map<String, String> extractTotalsByLabel(String text) {
        Map<String, String> found = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return found;
        }

        String sanitized = sanitizeTextForAmountExtraction(text);
        String[] lines = sanitized.split("\\R");
        Deque<String> pendingLabels = new ArrayDeque<>();

        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String field = detectTotalField(trimmed);
            String inlineAmount = extractAmountToken(trimmed);

            if (field != null) {
                if (inlineAmount != null) {
                    found.putIfAbsent(field, inlineAmount);
                } else {
                    pendingLabels.add(field);
                }
                continue;
            }

            if (!pendingLabels.isEmpty()) {
                String nextAmount = extractAmountToken(trimmed);
                if (nextAmount != null) {
                    String pendingField = pendingLabels.poll();
                    if (pendingField != null) {
                        found.putIfAbsent(pendingField, nextAmount);
                    }
                }
            }
        }

        return found;
    }

    private void reconcileInvoiceAmounts(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing) {
        Double amountHT = getExtractedAmount(extracted, "amountHT");
        Double tva = getExtractedAmount(extracted, "tva");
        Double amountTTC = getExtractedAmount(extracted, "amountTTC");

        if (amountHT == null || tva == null) {
            return;
        }

        double expectedTtc = round2(amountHT + tva);

        if (amountTTC == null) {
            upsertAmountField(extracted, "amountTTC", expectedTtc, 0.96, "AMOUNT_RECONCILIATION");
            missing.remove("amountTTC");
            log.info("TTC manquant -> recalcule via HT + TVA: {}", expectedTtc);
            return;
        }

        double difference = Math.abs(amountTTC - expectedTtc);
        boolean ttcEqualsTva = Math.abs(amountTTC - tva) <= 0.01;
        boolean ttcEqualsHt = Math.abs(amountTTC - amountHT) <= 0.01;
        boolean largeGap = expectedTtc > 0
                && difference > 0.50
                && (difference / expectedTtc) >= 0.10;

        if (ttcEqualsTva || ttcEqualsHt || largeGap) {
            upsertAmountField(extracted, "amountTTC", expectedTtc, 0.96, "AMOUNT_RECONCILIATION");
            missing.remove("amountTTC");
            log.warn("TTC incoherent (extrait={}, attendu={}) -> correction appliquee", amountTTC, expectedTtc);
        }
    }

    private Double getExtractedAmount(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String fieldName) {
        DynamicExtractionResult.ExtractedField field = extracted.get(fieldName);
        if (field == null) {
            return null;
        }
        String raw = field.getNormalizedValue() != null
                ? String.valueOf(field.getNormalizedValue())
                : field.getValue();
        return parseAmount(raw);
    }

    private void upsertAmountField(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String fieldName,
            double value,
            double confidence,
            String detectionMethod) {
        String formatted = formatAmount(value);
        extracted.put(fieldName,
                DynamicExtractionResult.ExtractedField.builder()
                        .value(formatted)
                        .normalizedValue(formatted)
                        .confidence(confidence)
                        .detectionMethod(detectionMethod)
                        .validated(confidence >= 0.7)
                        .validationError(confidence < 0.7 ? "Confidence faible" : null)
                        .build());
    }

    private String detectTotalField(String line) {
        String normalized = normalizeTextForMatching(line)
                .replace(" ", "");

        if (normalized.contains("montantnetttc")) {
            return "amountTTC";
        }
        if (normalized.contains("netapayer") && normalized.contains("ttc")) {
            return "amountTTC";
        }
        if (normalized.contains("netpayer") && normalized.contains("ttc")) {
            return "amountTTC";
        }
        if (normalized.contains("netapayer")) {
            return "amountTTC";
        }
        if (normalized.contains("netpayer")) {
            return "amountTTC";
        }
        if (normalized.startsWith("tva(") || normalized.startsWith("tva") || normalized.contains("totaltva")) {
            return "tva";
        }

        if (normalized.contains("total")) {
            if (normalized.contains("ttc")) {
                return "amountTTC";
            }
            if (normalized.contains("tva")) {
                return "tva";
            }
            if (normalized.contains("ht") || normalized.contains("horstaxe")) {
                return "amountHT";
            }
        }
        return null;
    }

    private String normalizeTextForMatching(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractAmountToken(String line) {
        Matcher matcher = Pattern.compile("(\\d[\\d\\s]{0,20}[,.]\\d{2})").matcher(line);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private List<String> extractAllTvaValues(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        String[] patterns = {
                "(?i)T\\.?V\\.?A\\.?\\s*\\(?\\s*\\d{1,2}\\s*%\\s*\\)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Total\\s+T\\.?V\\.?A\\.?\\s*(?:\\d{1,2}\\s*%)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Total\\s+tax(?:e|es|s)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})"
        };

        for (String regex : patterns) {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            while (matcher.find()) {
                String raw = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                String normalized = normalizeValue("tva", raw);
                if (parseAmount(normalized) != null) {
                    values.add(normalized);
                }
            }
        }

        return new ArrayList<>(values);
    }

    private List<String> extractAllHtValues(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        String[] patterns = {
                "(?i)(?:Total\\s+)?H\\.?T\\.?\\s*\\(?\\s*\\d{1,2}\\s*%\\s*\\)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)(?:Sous[-\\s]?total|Base|Montant)\\s+H\\.?T\\.?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)H\\.?T\\.?\\s*[:\\-]\\s*([\\d\\s]+[,.]\\d{2})"
        };

        for (String regex : patterns) {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            while (matcher.find()) {
                String raw = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                String normalized = normalizeValue("amountHT", raw);
                if (parseAmount(normalized) != null) {
                    values.add(normalized);
                }
            }
        }

        return new ArrayList<>(values);
    }

    private String normalizeValue(String fieldName, String value) {
        if (value == null)
            return null;

        switch (fieldName) {
            case "ice":
                return value.replaceAll("\\s", "");
            case "amountHT":
            case "tva":
            case "amountTTC":
                Double parsed = parseAmount(value);
                return parsed != null ? formatAmount(parsed) : value;
            case "invoiceDate":
                return normalizeInvoiceDate(value);
            case "invoiceNumber":
                String candidate = value.trim();
                String lower = candidate.toLowerCase(Locale.ROOT);
                if (!candidate.matches(".*\\d.*")) {
                    return null;
                }
                if (lower.equals("avoir") || lower.equals("credit") || lower.equals("facture")) {
                    return null;
                }
                return candidate;
            default:
                return value.trim();
        }
    }

    private void enforcePreferredInvoiceNumber(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return;
        }

        ExtractionAttempt preferred = tryPatterns(sourceText, Arrays.asList(
                "(?im)\\bN\\s*[^A-Za-z0-9]{0,3}\\s*de\\s*Facture\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                "(?im)\\b(?:Facture|FACTURE|Invoice|INVOICE)\\b\\s*(?:N\\s*[Â°Âºo]?|No\\.?|#|:)\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                "(?im)\\b(?:Facture|FACTURE|Invoice|INVOICE)\\b\\s+([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b"));

        if (preferred.value == null || preferred.value.isBlank()) {
            return;
        }

        String preferredNormalized = normalizeValue("invoiceNumber", preferred.value);
        if (preferredNormalized == null || preferredNormalized.isBlank()) {
            return;
        }

        DynamicExtractionResult.ExtractedField existingField = extracted.get("invoiceNumber");
        String existing = existingField != null ? String.valueOf(existingField.getValue()) : null;

        if (existing == null || existing.isBlank()) {
            addExtractedField(extracted, "invoiceNumber", preferredNormalized, preferred.confidence);
            return;
        }

        if (isLikelyReferenceNumber(existing) && !isLikelyReferenceNumber(preferredNormalized)) {
            addExtractedField(extracted, "invoiceNumber", preferredNormalized, preferred.confidence);
            log.info("Numero facture priorise: {} (au lieu de {})", preferredNormalized, existing);
        }
    }

    private boolean isLikelyReferenceNumber(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("\\d{1,4}/\\d{1,4}")) {
            return true;
        }
        return !normalized.matches(".*[A-Z].*");
    }

    private String normalizeInvoiceDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String cleaned = raw.trim();
        cleaned = cleaned.replaceAll("[Oo]", "0");
        cleaned = cleaned.replaceAll("[Il]", "1");
        cleaned = cleaned.replaceAll("[^0-9/\\-.\\s]", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        Matcher separated = Pattern.compile("(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})\\s*[/\\-.]\\s*(\\d{2,4})")
                .matcher(cleaned);
        if (separated.find()) {
            int day = Integer.parseInt(separated.group(1));
            int month = Integer.parseInt(separated.group(2));
            int year = Integer.parseInt(separated.group(3));
            if (year < 100) {
                int currentYear = java.time.LocalDate.now().getYear();
                int candidateYear = 2000 + year;
                if (candidateYear > currentYear + 1 || candidateYear < currentYear - 15) {
                    return null;
                }
                year = candidateYear;
            }
            if (isPlausibleDate(day, month, year)) {
                return String.format(Locale.US, "%02d/%02d/%04d", day, month, year);
            }
        }

        String digits = cleaned.replaceAll("[^0-9]", "");
        if (digits.length() == 10 && digits.charAt(2) == '1' && digits.charAt(5) == '1') {
            digits = "" + digits.charAt(0) + digits.charAt(1) + digits.charAt(3) + digits.charAt(4)
                    + digits.substring(6);
        }
        if (digits.length() == 8) {
            int day = Integer.parseInt(digits.substring(0, 2));
            int month = Integer.parseInt(digits.substring(2, 4));
            int year = Integer.parseInt(digits.substring(4, 8));
            if (isPlausibleDate(day, month, year)) {
                return String.format(Locale.US, "%02d/%02d/%04d", day, month, year);
            }
        }

        return null;
    }

    private boolean isPlausibleDate(int day, int month, int year) {
        return day >= 1 && day <= 31 && month >= 1 && month <= 12 && year >= 2000 && year <= 2100;
    }

    private String extractDateFromLabeledContext(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Matcher matcher = Pattern.compile(
                "(?im)(?:\\bDate\\b|\\bDATE\\b|\\bLe\\b)\\s*[:\\-]?\\s*([^\\n]{0,40})")
                .matcher(text);

        while (matcher.find()) {
            String window = matcher.group(1);
            if (window == null || window.isBlank()) {
                continue;
            }

            Matcher dateMatcher = Pattern
                    .compile("([0-9OIl]{1,2})\\s*[/\\-.]\\s*([0-9OIl]{1,2})\\s*[/\\-.]\\s*([0-9OIl]{2,4})")
                    .matcher(window);
            if (dateMatcher.find()) {
                String dateCandidate = dateMatcher.group(1) + "/" + dateMatcher.group(2) + "/" + dateMatcher.group(3);
                String normalized = normalizeInvoiceDate(dateCandidate);
                if (normalized != null && normalized.matches("\\d{2}/\\d{2}/\\d{4}")) {
                    return normalized;
                }
            }
        }

        return null;
    }

    private String extractDateFromHeaderHeuristic(String headerText) {
        if (headerText == null || headerText.isBlank()) {
            return null;
        }

        Matcher matcher = Pattern
                .compile("(?<!\\d)([0-9OIl]{1,2})\\s*[/\\-.]\\s*([0-9OIl]{1,2})\\s*[/\\-.]\\s*([0-9OIl]{2,4})(?!\\d)")
                .matcher(headerText);

        while (matcher.find()) {
            String raw = matcher.group(1) + "/" + matcher.group(2) + "/" + matcher.group(3);
            String normalized = normalizeInvoiceDate(raw);
            if (normalized != null && normalized.matches("\\d{2}/\\d{2}/\\d{4}")) {
                return normalized;
            }
        }

        return null;
    }

    private Double parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String cleaned = raw.trim()
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replaceAll("\\s+", "");

        cleaned = cleaned.replaceAll("(?<=\\d)[Oo](?=[\\d,\\.])", "0");
        cleaned = cleaned.replaceAll("(?<=[\\d,\\.])[Oo](?=\\d)", "0");
        cleaned = cleaned.replaceAll("(?<=\\d)[Il](?=\\d)", "1");
        cleaned = cleaned.replaceAll("[^0-9,\\.\\-]", "");

        if (cleaned.isBlank() || "-".equals(cleaned)) {
            return null;
        }

        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                cleaned = cleaned.replace(".", "");
                cleaned = cleaned.replace(',', '.');
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (lastComma >= 0) {
            cleaned = cleaned.replace(',', '.');
        }

        int firstDot = cleaned.indexOf('.');
        while (firstDot != -1 && firstDot != cleaned.lastIndexOf('.')) {
            cleaned = cleaned.substring(0, firstDot) + cleaned.substring(firstDot + 1);
            firstDot = cleaned.indexOf('.');
        }

        try {
            return round2(Double.parseDouble(cleaned));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatAmount(double value) {
        return String.format(Locale.US, "%.2f", round2(value));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void addMissingField(List<String> missing, String fieldName) {
        if (!missing.contains(fieldName)) {
            missing.add(fieldName);
        }
    }

    private void addLowConfidenceField(List<String> lowConfidence, String fieldName) {
        if (!lowConfidence.contains(fieldName)) {
            lowConfidence.add(fieldName);
        }
    }

    private ExtractionAttempt extractField(String text, DynamicFieldDefinitionJson def) {
        if (def.getRegexPattern() == null || def.getRegexPattern().isBlank()) {
            return new ExtractionAttempt(null, 0.0);
        }

        try {
            Pattern pattern = Pattern.compile(def.getRegexPattern(), Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                return new ExtractionAttempt(value.trim(), 0.9);
            }
        } catch (Exception e) {
            log.warn("Regex invalide pour {} : {}", def.getFieldName(), e.getMessage());
        }

        return new ExtractionAttempt(null, 0.0);
    }

    private static class ExtractionAttempt {
        String value;
        double confidence;

        ExtractionAttempt(String value, double confidence) {
            this.value = value;
            this.confidence = confidence;
        }
    }

    private static class AmountCandidate {
        final String normalizedValue;
        final double numericValue;
        final double score;

        AmountCandidate(String normalizedValue, double numericValue, double score) {
            this.normalizedValue = normalizedValue;
            this.numericValue = numericValue;
            this.score = score;
        }
    }

    private String extractSupplierSmart(String header, String footer, String fullText) {
        String supplier = extractSupplierFromHeader(header);
        if (supplier != null)
            return supplier;

        supplier = extractSupplierFromFooter(footer);
        if (supplier != null)
            return supplier;

        supplier = extractSupplierGeneric(fullText);
        return supplier;
    }

    private String extractSupplierFromHeader(String header) {
        if (header == null || header.isBlank())
            return null;

        Pattern deBlockPattern = Pattern.compile(
                "(?im)(?:^|\\n)\\s*(?:DE|FROM)\\s*\\n+\\s*([A-Z][A-Za-z0-9\\s&.,'()\\-/]{2,60})");
        Matcher deBlockMatcher = deBlockPattern.matcher(header);
        if (deBlockMatcher.find()) {
            String candidate = deBlockMatcher.group(1).trim();
            if (isValidSupplierCandidate(candidate)) {
                return candidate;
            }
        }

        Pattern multiLinePattern = Pattern.compile(
                "^\\s*([A-Z][A-Z\\s]{2,40})\\s*(?:\\n|\\r\\n)+\\s*([A-Z][A-Z\\s]{2,40})",
                Pattern.MULTILINE);

        Matcher matcher = multiLinePattern.matcher(header);
        if (matcher.find()) {
            String line1 = matcher.group(1).trim();
            String line2 = matcher.group(2).trim();
            String candidate = (line1 + " " + line2).replaceAll("\\s+", " ");
            if (isValidSupplierCandidate(candidate)) {
                return candidate;
            }
        }

        Pattern singleLinePattern = Pattern.compile(
                "^\\s*([A-Z][A-Z\\s&]{3,50})\\s*$",
                Pattern.MULTILINE);

        matcher = singleLinePattern.matcher(header);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (isValidSupplierCandidate(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private String extractSupplierGeneric(String text) {
        if (text == null || text.isBlank())
            return null;

        Pattern deInlinePattern = Pattern.compile(
                "(?i)(?:^|\\n)\\s*(?:DE|FROM)\\s*[:\\-]?\\s*([A-Z][A-Za-z0-9\\s&.,'()\\-/]{2,60})");
        Matcher deInlineMatcher = deInlinePattern.matcher(text);
        if (deInlineMatcher.find()) {
            String supplier = deInlineMatcher.group(1).trim();
            if (isValidSupplierCandidate(supplier)) {
                return supplier;
            }
        }

        Pattern pattern = Pattern.compile(
                "([A-Z][A-Z\\s&]{3,50})(?:\\s+(?:SARL|SAS|SA|S\\.A\\.R\\.L|S\\.A))?",
                Pattern.MULTILINE);

        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String supplier = matcher.group(1).trim();
            if (isValidSupplierCandidate(supplier)) {
                return supplier;
            }
        }

        return null;
    }

    private boolean isValidSupplierCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }

        String normalized = candidate.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 80) {
            return false;
        }

        String[] forbidden = {
                "FACTURE", "INVOICE", "TOTAL", "TAXE", "TAX", "DRAFT",
                "FACTURE A", "FACTURÃ‰ A", "FACTURE Ã€", "BILL TO", "ARTICLE",
                "DESCRIPTION", "QTE", "QTÃ‰", "PRIX", "MONTANT", "DATE"
        };
        for (String token : forbidden) {
            if (normalized.contains(token)) {
                return false;
            }
        }

        return true;
    }

    private String sanitizeTextForSupplierIdentifiers(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String normalizedText = text.replace("\\", "");

        StringBuilder out = new StringBuilder();
        int skipLines = 0;
        for (String line : normalizedText.split("\\r?\\n")) {
            if (skipLines > 0) {
                if (line.trim().isEmpty()) {
                    skipLines = 0;
                } else {
                    skipLines--;
                }
                continue;
            }

            if (line.matches(
                    "(?i).*\\b(Nom\\s*de\\s*client|Client|Factur[eÃ©]\\s*[aÃ ]|Factur[eÃ©]\\s*Ã |FACTUR[EÃ‰]\\s*[AÃ€]|BILL\\s+TO|CUSTOMER)\\b.*")) {
                skipLines = 5;
                continue;
            }

            out.append(line).append('\n');
        }

        return out.toString();
    }

    private String extractIceLoose(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Pattern pattern = Pattern.compile("(?i)(?:I\\.?\\s*C\\.?\\s*E|ICE|1CE|LCE)\\s*[:.]?\\s*([0-9\\s\\.]{10,30})");
        Matcher matcher = pattern.matcher(text);
        String lastDigits = null;
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate == null) {
                continue;
            }
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() == 15) {
                lastDigits = digits;
            }
        }

        return lastDigits;
    }

    private String extractIceByProximity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Pattern keywordPattern = Pattern.compile("(?i)(?:I\\s*\\.?\\s*C\\s*\\.?\\s*E|ICE|1CE|LCE)");
        Matcher keywordMatcher = keywordPattern.matcher(text);
        String lastDigits = null;
        while (keywordMatcher.find()) {
            int start = keywordMatcher.end();
            int end = Math.min(text.length(), start + 100);
            String window = text.substring(start, end);
            Matcher numberMatcher = Pattern.compile("([0-9\\s\\.]{10,30})").matcher(window);
            while (numberMatcher.find()) {
                String candidate = numberMatcher.group(1);
                if (candidate == null) {
                    continue;
                }
                String digits = candidate.replaceAll("\\D", "");
                if (digits.length() == 15) {
                    lastDigits = digits;
                }
            }
        }

        // Fallback: any 15-digit sequence in footer-like text
        Matcher loose = Pattern.compile("([0-9\\s\\.]{10,30})").matcher(text);
        while (loose.find()) {
            String candidate = loose.group(1);
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() == 15) {
                lastDigits = digits;
            }
        }

        return lastDigits;
    }

    private String extractLastMatchStrict(String text, List<String> patterns) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String lastMatch = null;
        int lastPosition = -1;

        for (String patternStr : patterns) {
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);

                while (matcher.find()) {
                    String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();

                    if (value != null && !value.isBlank()) {
                        String normalized = value.replaceAll("\\s+", "");

                        if (!normalized.isEmpty()) {
                            int position = matcher.start();

                            if (position > lastPosition) {
                                lastMatch = normalized;
                                lastPosition = position;
                                log.debug("Nouveau match trouvÃƒÆ’Ã‚Â© ÃƒÆ’Ã‚Â  position {}: {} (pattern: {})",
                                        position, lastMatch, patternStr);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.warn("Erreur avec le pattern '{}': {}", patternStr, e.getMessage());
            }
        }

        if (lastMatch != null) {
            log.debug("DERNIER match retenu ÃƒÆ’Ã‚Â  position {}: {}", lastPosition, lastMatch);
        } else {
            log.debug("Aucun match trouvÃƒÆ’Ã‚Â© dans tous les patterns");
        }

        return lastMatch;
    }

    /**
     * ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ NOUVELLE MÃƒÆ’Ã¢â‚¬Â°THODE: Extrait le texte d'une zone
     * spÃƒÆ’Ã‚Â©cifique en utilisant les
     * marqueurs
     * Exemple: extractZoneText(ocrText, "FOOTER") retourne le texte entre [FOOTER]
     * et la fin/prochain marqueur
     */
    private String extractZoneText(String ocrText, String zoneName) {
        if (ocrText == null || ocrText.isBlank()) {
            return "";
        }

        String startMarker = "[" + zoneName + "]";
        int startIndex = ocrText.indexOf(startMarker);

        if (startIndex == -1) {
            log.debug("Marqueur {} non trouvÃƒÆ’Ã‚Â© dans le texte OCR", startMarker);
            return null; // Pas de marqueur trouvÃƒÆ’Ã‚Â©
        }

        // Commencer aprÃƒÆ’Ã‚Â¨s le marqueur
        startIndex += startMarker.length();

        // Trouver le prochain marqueur de zone (ou fin du texte)
        int endIndex = ocrText.length();

        // Chercher les autres marqueurs de zone
        String[] otherZones = { "[HEADER]", "[BODY]", "[FOOTER]" };
        for (String otherMarker : otherZones) {
            if (otherMarker.equals(startMarker))
                continue;

            int otherIndex = ocrText.indexOf(otherMarker, startIndex);
            if (otherIndex != -1 && otherIndex < endIndex) {
                endIndex = otherIndex;
            }
        }

        String zoneText = ocrText.substring(startIndex, endIndex).trim();
        log.debug("Zone {} extraite: {} caractÃƒÆ’Ã‚Â¨res", zoneName, zoneText.length());

        return zoneText;
    }

    /**
     * Tente d'extraire ICE, IF et RC ensemble s'ils apparaissent dans le
     * mÃƒÆ’Ã‚Âªme
     * bloc/ligne
     */
    private void extractUnifiedIdentifiers(String text, Map<String, DynamicExtractionResult.ExtractedField> extracted) {
        // Pattern spÃƒÆ’Ã‚Â©cifique pour le format demandÃƒÆ’Ã‚Â©: "NÃƒâ€šÃ‚Â° ICE: ...
        // NÃƒâ€šÃ‚Â° RC: ... IF NÃƒâ€šÃ‚Â°:
        // ..."
        // On gÃƒÆ’Ã‚Â¨re l'ordre variable car ÃƒÆ’Ã‚Â§a peut changer selon l'OCR
        String[] combinedPatterns = {
                // ICE -> RC -> IF
                "(?i)ICE\\s*[:.]?\\s*(\\d{15}).*?RC\\s*[:.]?\\s*(\\d{4,10}).*?IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10})",
                // ICE -> IF -> RC
                "(?i)ICE\\s*[:.]?\\s*(\\d{15}).*?IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?RC\\s*[:.]?\\s*(\\d{4,10})",
                // RC -> ICE -> IF
                "(?i)RC\\s*[:.]?\\s*(\\d{4,10}).*?ICE\\s*[:.]?\\s*(\\d{15}).*?IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10})",
                // RC -> IF -> ICE
                "(?i)RC\\s*[:.]?\\s*(\\d{4,10}).*?IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?ICE\\s*[:.]?\\s*(\\d{15})",
                // IF -> ICE -> RC
                "(?i)IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?ICE\\s*[:.]?\\s*(\\d{15}).*?RC\\s*[:.]?\\s*(\\d{4,10})",
                // IF -> RC -> ICE
                "(?i)IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?RC\\s*[:.]?\\s*(\\d{4,10}).*?ICE\\s*[:.]?\\s*(\\d{15})"
        };

        for (String patternStr : combinedPatterns) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                log.info("ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ Bloc identifiant unifiÃƒÆ’Ã‚Â© trouvÃƒÆ’Ã‚Â©!");

                String block = matcher.group(0);

                String ice = extractLastMatchStrict(block, Arrays.asList(ExtractionPatterns.ICE_PATTERNS));
                String ifNum = extractLastMatchStrict(block, Arrays.asList(ExtractionPatterns.IF_PATTERNS));
                String rc = extractLastMatchStrict(block, Arrays.asList(ExtractionPatterns.RC_PATTERNS));

                if (ice != null)
                    addExtractedField(extracted, "ice", ice.replaceAll("\\s+", ""), 0.98);
                if (ifNum != null)
                    addExtractedField(extracted, "ifNumber", ifNum.replaceAll("\\s+", ""), 0.98);
                if (rc != null)
                    addExtractedField(extracted, "rcNumber", rc.replaceAll("\\s+", ""), 0.98);

                return;
            }
        }
    }
}
