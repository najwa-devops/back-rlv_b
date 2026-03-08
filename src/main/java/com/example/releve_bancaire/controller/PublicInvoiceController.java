package com.example.releve_bancaire.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.releve_bancaire.dto.ocr.OcrResult;
import com.example.releve_bancaire.servises.dynamic.DynamicExtractionResult;
import com.example.releve_bancaire.servises.dynamic.DynamicFieldExtractorService;
import com.example.releve_bancaire.servises.ocr.AdvancedOcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/public/invoices")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class PublicInvoiceController {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AdvancedOcrService advancedOcrService;
    private final DynamicFieldExtractorService dynamicFieldExtractorService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadAndProcess(@RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> metadata = processSingleUpload(file);
            return ResponseEntity.ok(toResponse(metadata));
        } catch (Throwable e) {
            log.error("Public upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/upload/batch")
    public ResponseEntity<?> uploadBatch(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty_files"));
        }

        List<Map<String, Object>> uploaded = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalName = file != null ? file.getOriginalFilename() : null;
            try {
                Map<String, Object> metadata = processSingleUpload(file);
                uploaded.add(toResponse(metadata));
            } catch (Throwable ex) {
                String filename = originalName != null ? originalName : "unknown";
                log.warn("Batch item failed for {}: {}", filename, ex.getMessage());
                errors.add(Map.of(
                        "filename", filename,
                        "error", ex.getMessage()));
            }
        }

        return ResponseEntity.ok(Map.of(
                "count", uploaded.size(),
                "errorCount", errors.size(),
                "invoices", uploaded,
                "errors", errors));
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            List<Map<String, Object>> all = readAllMetadata();
            Stream<Map<String, Object>> stream = all.stream();

            if (status != null && !status.isBlank()) {
                String normalized = status.toUpperCase(Locale.ROOT);
                stream = stream
                        .filter(item -> normalized.equals(String.valueOf(item.get("status")).toUpperCase(Locale.ROOT)));
            }

            List<Map<String, Object>> response = stream
                    .sorted(Comparator
                            .comparing((Map<String, Object> m) -> String.valueOf(m.getOrDefault("createdAt", "")))
                            .reversed())
                    .limit(Math.max(1, limit))
                    .map(this::toResponse)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "count", response.size(),
                    "invoices", response));
        } catch (Exception e) {
            log.error("Public list failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        try {
            Path metadataPath = getUploadDirectory().resolve(id + ".json");
            if (!Files.exists(metadataPath)) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> metadata = objectMapper.readValue(metadataPath.toFile(), MAP_TYPE);
            return ResponseEntity.ok(toDetailedResponse(metadata));
        } catch (Exception e) {
            log.error("Public getById failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<?> downloadFile(@PathVariable Long id) {
        try {
            Path metadataPath = getUploadDirectory().resolve(id + ".json");
            if (!Files.exists(metadataPath)) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> metadata = objectMapper.readValue(metadataPath.toFile(), MAP_TYPE);
            Object filePathObj = metadata.get("filePath");
            if (filePathObj == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "file_path_missing"));
            }

            Path filePath = Paths.get(String.valueOf(filePathObj)).normalize();
            if (!Files.exists(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "file_not_found"));
            }

            Resource resource = new UrlResource(filePath.toUri());
            String contentType = Files.probeContentType(filePath);
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filePath.getFileName() + "\"")
                    .body(resource);
        } catch (Exception e) {
            log.error("Public file download failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private List<Map<String, Object>> readAllMetadata() throws IOException {
        List<Map<String, Object>> invoices = new ArrayList<>();
        Path dir = getUploadDirectory();
        if (!Files.exists(dir)) {
            return invoices;
        }

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            Map<String, Object> item = objectMapper.readValue(path.toFile(), MAP_TYPE);
                            invoices.add(item);
                        } catch (IOException e) {
                            log.warn("Failed to parse metadata file {}: {}", path, e.getMessage());
                        }
                    });
        }

        return invoices;
    }

    private Path getUploadDirectory() {
        return Paths.get(System.getProperty("user.home"), "Pictures", "Invoices", "Invoices", "uploads", "public");
    }

    private Map<String, Object> processSingleUpload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("empty_file");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !isValidFileType(originalName)) {
            throw new IllegalArgumentException("unsupported_file_type");
        }

        Path uploadDir = getUploadDirectory();
        Files.createDirectories(uploadDir);

        String timestamp = LocalDateTime.now().format(TS_FORMAT);
        String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedName = timestamp + "_" + safeName;
        Path storedPath = uploadDir.resolve(storedName);
        file.transferTo(storedPath.toFile());

        long id = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        String nowText = now.toString();

        OcrResult ocrResult;
        try {
            ocrResult = advancedOcrService.extractTextAdvanced(storedPath);
        } catch (Throwable ex) {
            log.warn("OCR failed in local mode for {}: {}", storedName, ex.getMessage());
            ocrResult = OcrResult.failed(ex.getMessage());
        }

        String rawText = ocrResult.getText() != null ? ocrResult.getText() : "";
        Map<String, Object> fieldsData = extractHighQualityFields(rawText);
        enrichWithComptesByIce(fieldsData);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", id);
        metadata.put("filename", storedName);
        metadata.put("originalName", originalName);
        metadata.put("filePath", storedPath.toString());
        metadata.put("fileSize", file.getSize());
        metadata.put("status", ocrResult.isSuccess() ? "TREATED" : "ERROR");
        metadata.put("overallConfidence", ocrResult.getConfidence() / 100.0);
        metadata.put("rawOcrText", rawText);
        metadata.put("fieldsData", fieldsData);
        metadata.put("createdAt", nowText);
        metadata.put("updatedAt", nowText);

        Path metadataPath = uploadDir.resolve(id + ".json");
        objectMapper.writeValue(metadataPath.toFile(), metadata);

        return metadata;
    }

    private boolean isValidFileType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private Map<String, Object> toResponse(Map<String, Object> invoice) {
        Map<String, Object> fieldsData = getFieldsData(invoice);
        String id = invoice.get("id") != null ? String.valueOf(invoice.get("id")) : null;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", invoice.get("id"));
        response.put("filename", invoice.get("filename"));
        response.put("originalName", invoice.get("originalName"));
        response.put("status", invoice.getOrDefault("status", "PENDING"));
        response.put("templateId", null);
        response.put("templateName", null);
        response.put("overallConfidence", invoice.get("overallConfidence"));
        response.put("fieldsData", fieldsData);
        response.put("invoiceNumber", firstNonBlank(
                getStringValue(fieldsData, "invoiceNumber"),
                getStringValue(fieldsData, "numeroFacture")));
        response.put("supplier", firstNonBlank(
                getStringValue(fieldsData, "supplier"),
                getStringValue(fieldsData, "fournisseur")));
        response.put("invoiceDate", firstNonBlank(
                getStringValue(fieldsData, "invoiceDate"),
                getStringValue(fieldsData, "dateFacture"),
                getStringValue(fieldsData, "date")));
        response.put("ice", getStringValue(fieldsData, "ice"));
        response.put("ifNumber", getStringValue(fieldsData, "ifNumber"));
        response.put("rcNumber", getStringValue(fieldsData, "rcNumber"));
        response.put("amountHT", getDoubleValue(fieldsData, "amountHT"));
        response.put("tva", getDoubleValue(fieldsData, "tva"));
        response.put("amountTTC", getDoubleValue(fieldsData, "amountTTC"));
        response.put("tvaRate", getStringValue(fieldsData, "tvaRate"));
        response.put("fileUrl", id != null ? "/api/public/invoices/" + id + "/file" : null);
        response.put("createdAt", invoice.get("createdAt"));
        response.put("updatedAt", invoice.get("updatedAt"));
        return response;
    }

    private Map<String, Object> toDetailedResponse(Map<String, Object> invoice) {
        Map<String, Object> response = new LinkedHashMap<>(toResponse(invoice));
        response.put("rawOcrText", invoice.getOrDefault("rawOcrText", ""));
        response.put("extractedData", new LinkedHashMap<>());
        response.put("missingFields", List.of());
        response.put("lowConfidenceFields", List.of());
        response.put("autoFilledFields", List.of());
        response.put("filePath", invoice.get("filePath"));
        response.put("fileSize", invoice.get("fileSize"));
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getFieldsData(Map<String, Object> invoice) {
        Object raw = invoice.get("fieldsData");
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            rawMap.forEach((k, v) -> mapped.put(String.valueOf(k), v));
            return mapped;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> extractHighQualityFields(String rawText) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (rawText == null || rawText.isBlank()) {
            return merged;
        }

        try {
            DynamicExtractionResult advanced = dynamicFieldExtractorService.extractWithoutTemplate(rawText);
            if (advanced != null) {
                merged.putAll(advanced.toSimpleMap());
            }
        } catch (Exception ex) {
            log.warn("Dynamic extractor failed in local mode: {}", ex.getMessage());
        }

        // Keep previous lightweight fallback for any still-missing fields.
        Map<String, Object> basic = extractBasicFields(rawText);
        for (Map.Entry<String, Object> entry : basic.entrySet()) {
            Object existing = merged.get(entry.getKey());
            if (existing == null || existing.toString().isBlank()) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }

        return merged;
    }

    private void enrichWithComptesByIce(Map<String, Object> fieldsData) {
        if (fieldsData == null || fieldsData.isEmpty()) {
            return;
        }

        String rawIce = getStringValue(fieldsData, "ice");
        String ice = normalizeIce(rawIce);
        if (ice == null) {
            return;
        }

        Map<String, Object> compte = findCompteByIce(ice);
        if (compte == null || compte.isEmpty()) {
            return;
        }

        String numero = firstNonBlank(
                getStringValue(compte, "numero"),
                getStringValue(compte, "code"),
                getStringValue(compte, "num"));
        String tvaCompte = firstNonBlank(
                getStringValue(compte, "tva"),
                getStringValue(compte, "va"),
                getStringValue(compte, "tva_rate"),
                getStringValue(compte, "tvaRate"));
        String chargeCompte = firstNonBlank(
                getStringValue(compte, "charge"),
                getStringValue(compte, "chrge"),
                getStringValue(compte, "compt_ht"),
                getStringValue(compte, "ht"));
        String libelle = firstNonBlank(
                getStringValue(compte, "libelle"),
                getStringValue(compte, "label"),
                getStringValue(compte, "name"));
        String activite = firstNonBlank(
                getStringValue(compte, "activite"),
                getStringValue(compte, "activity"));

        if (numero != null) {
            fieldsData.put("tierNumber", numero);
            fieldsData.put("comptTier", numero);
        }
        if (tvaCompte != null) {
            fieldsData.put("tvaAccount", tvaCompte);
            fieldsData.put("comptTva", tvaCompte);
        }
        if (chargeCompte != null) {
            fieldsData.put("chargeAccount", chargeCompte);
            fieldsData.put("comptHt", chargeCompte);
        }

        String supplierFromCompte = buildSupplierFromCompte(libelle, activite);
        if (supplierFromCompte != null) {
            fieldsData.put("supplier", supplierFromCompte);
        }
    }

    private String buildSupplierFromCompte(String libelle, String activite) {
        if ((libelle == null || libelle.isBlank()) && (activite == null || activite.isBlank())) {
            return null;
        }
        if (libelle != null && !libelle.isBlank() && activite != null && !activite.isBlank()) {
            return libelle + " - " + activite;
        }
        return libelle != null && !libelle.isBlank() ? libelle : activite;
    }

    private String normalizeIce(String rawIce) {
        if (rawIce == null || rawIce.isBlank()) {
            return null;
        }
        String normalized = rawIce.replaceAll("\\D", "");
        if (normalized.length() != 15) {
            return null;
        }
        return normalized;
    }

    private Map<String, Object> findCompteByIce(String normalizedIce) {
        String table = resolveComptesTable();
        if (table == null || !hasColumn(table, "ice")) {
            return null;
        }

        String sql = "SELECT * FROM " + table + " " +
                "WHERE CAST(ice AS CHAR) = ? " +
                "OR REPLACE(REPLACE(REPLACE(CAST(ice AS CHAR), ' ', ''), '.', ''), '-', '') = ? " +
                "LIMIT 1";

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, normalizedIce, normalizedIce);
            if (rows.isEmpty()) {
                return null;
            }
            return rows.get(0);
        } catch (DataAccessException ex) {
            log.warn("Failed comptes ICE lookup: {}", ex.getMessage());
            return null;
        }
    }

    private String resolveComptesTable() {
        if (tableExists("comptes")) {
            return "comptes";
        }
        if (tableExists("accounts")) {
            return "accounts";
        }
        return null;
    }

    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class,
                    tableName);
            return count != null && count > 0;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    private boolean hasColumn(String tableName, String columnName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                    Integer.class,
                    tableName,
                    columnName);
            return count != null && count > 0;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    private Map<String, Object> extractBasicFields(String rawText) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (rawText == null || rawText.isBlank()) {
            return fields;
        }

        String text = rawText;
        String normalizedText = normalizeOcrText(text);

        String ice = extractLastMatchStrict(normalizedText, List.of(
                "(?i)ICE\\s*[:.]?\\s*((?:\\d[\\s\\.]*){15})",
                "(?i)I\\s*\\.?\\s*C\\s*\\.?\\s*E\\s*\\.?\\s*[:.]?\\s*((?:\\d[\\s\\.]*){15})",
                "\\b(\\d{15})\\b"));
        if (ice != null) {
            fields.put("ice", ice);
        }

        String ifNumber = extractLastMatchStrict(normalizedText, List.of(
                "(?i)(?:I\\s*\\.?\\s*F\\.?|L\\s*\\.?\\s*F\\.?|IF|IDENTIFIANT\\s+FISCAL)\\s*(?:N\\s*[°ºo0])?\\s*[:#-]?\\s*['`’\"]?\\s*(\\d{6,12})",
                "(?i)\\b(?:I\\.?F\\.?|L\\.?F\\.?)\\b\\s*[:#-]?\\s*(\\d{6,12})"));
        if (ifNumber != null) {
            fields.put("ifNumber", ifNumber);
        }

        String rcNumber = extractLastMatchStrict(normalizedText, List.of(
                "(?i)(?:R\\s*\\.?\\s*C\\.?|RC)\\s*(?:N\\s*[°ºo0])?\\s*[:#-]?\\s*['`’\"]?\\s*(\\d{3,12})",
                "(?i)Registre\\s+(?:de\\s+)?Commerce\\s*[:#-]?\\s*(\\d{3,12})"));
        if (rcNumber != null) {
            fields.put("rcNumber", rcNumber);
        }

        String invoiceNumber = extractInvoiceNumber(normalizedText);
        if (invoiceNumber != null) {
            fields.put("invoiceNumber", invoiceNumber);
        }

        String invoiceDate = extractInvoiceDate(normalizedText);
        if (invoiceDate != null) {
            fields.put("invoiceDate", invoiceDate);
        }

        String supplier = extractSupplier(normalizedText);
        if (supplier != null) {
            fields.put("supplier", supplier);
        }

        Map<String, Double> totals = extractTotalsByLabel(normalizedText);
        if (totals.get("amountHT") != null) {
            fields.put("amountHT", totals.get("amountHT"));
        }
        if (totals.get("tva") != null) {
            fields.put("tva", totals.get("tva"));
        }
        if (totals.get("amountTTC") != null) {
            fields.put("amountTTC", totals.get("amountTTC"));
        }

        Double amountHT = (Double) fields.get("amountHT");
        Double tva = (Double) fields.get("tva");
        Double amountTTC = (Double) fields.get("amountTTC");
        if (amountTTC == null && amountHT != null && tva != null) {
            fields.put("amountTTC", round2(amountHT + tva));
        }

        String tvaRate = findFirst(normalizedText, "(?i)tva\\s*(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*%");
        if (tvaRate != null) {
            fields.put("tvaRate", tvaRate.replace(",", "."));
        }

        return fields;
    }

    private String extractSupplier(String text) {
        String[] lines = text.split("\\R");
        for (String line : lines) {
            String normalized = line != null ? line.trim() : "";
            if (normalized.length() < 4) {
                continue;
            }
            String upper = normalized.toUpperCase(Locale.ROOT);
            if (upper.contains("SARL") || upper.contains("SA ") || upper.startsWith("STE")
                    || upper.startsWith("SOCIETE")) {
                return normalized;
            }
        }
        return null;
    }

    private String extractInvoiceDate(String text) {
        String[] lines = text.split("\\R");
        List<String> invoiceDateKeywords = List.of("FACTURATION", "FACTURE");
        List<String> lowPriorityKeywords = List.of("ECHEANCE", "DUE");

        // Pass 1: prioritize invoice date lines.
        for (String line : lines) {
            String raw = line != null ? line.trim() : "";
            if (raw.isBlank()) {
                continue;
            }
            String upper = raw.toUpperCase(Locale.ROOT);
            boolean hasPriority = invoiceDateKeywords.stream().anyMatch(upper::contains);
            boolean hasLowPriority = lowPriorityKeywords.stream().anyMatch(upper::contains);
            if (!hasPriority || hasLowPriority) {
                continue;
            }
            String afterColon = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
            String date = normalizeDateCandidate(afterColon);
            if (date != null) {
                return date;
            }
        }

        // Pass 2: generic date lines excluding due date keywords when possible.
        for (String line : lines) {
            String raw = line != null ? line.trim() : "";
            if (raw.isBlank()) {
                continue;
            }
            String upper = raw.toUpperCase(Locale.ROOT);
            if (!upper.contains("DATE")) {
                continue;
            }
            boolean hasLowPriority = lowPriorityKeywords.stream().anyMatch(upper::contains);
            if (hasLowPriority) {
                continue;
            }

            String afterColon = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
            String date = normalizeDateCandidate(afterColon);
            if (date != null) {
                return date;
            }
        }

        // Fallback: first detected date-like sequence in full text.
        String direct = findFirst(text, "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\b");
        if (direct != null) {
            return normalizeDateCandidate(direct);
        }
        return null;
    }

    private String findFirst(String text, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find() && matcher.groupCount() >= 1) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractInvoiceNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String[] lines = text.split("\\R");
        Pattern numberPattern = Pattern
                .compile("(?i)(?:FACTURE|INVOICE)[^\\n\\r]*?(?:N\\s*[°ºo#]?\\s*)?[:#-]?\\s*([A-Z0-9][A-Z0-9\\-/]{2,})");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Matcher matcher = numberPattern.matcher(line);
            if (matcher.find()) {
                String candidate = matcher.group(1);
                if (!isDateLike(candidate)) {
                    return candidate;
                }
            }
        }

        String fallback = findFirst(text,
                "(?i)(?:FACTURE|INVOICE|N\\s*[°ºo#]?\\s*FACTURE|NUMERO)\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9\\-/]{2,})");
        return isDateLike(fallback) ? null : fallback;
    }

    private boolean isDateLike(String value) {
        if (value == null) {
            return false;
        }
        return value.matches("^\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}$");
    }

    private String extractLastMatchStrict(String text, List<String> patterns) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lastMatch = null;
        int lastPos = -1;
        for (String regex : patterns) {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                if (value == null || value.isBlank()) {
                    continue;
                }
                String normalized = value.replaceAll("\\D", "");
                if (normalized.isBlank()) {
                    continue;
                }
                if (matcher.start() > lastPos) {
                    lastPos = matcher.start();
                    lastMatch = normalized;
                }
            }
        }
        return lastMatch;
    }

    private String normalizeDateCandidate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .trim();

        String direct = findFirst(cleaned, "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\b");
        if (direct != null) {
            return normalizeSplitDate(direct);
        }

        String digits = cleaned.replaceAll("\\D", "");
        String compact = toEightDigitsDate(digits);
        if (compact == null || compact.length() != 8) {
            return null;
        }

        int day = Integer.parseInt(compact.substring(0, 2));
        int month = Integer.parseInt(compact.substring(2, 4));
        int year = Integer.parseInt(compact.substring(4, 8));
        if (day < 1 || day > 31 || month < 1 || month > 12 || year < 1900 || year > 2100) {
            return null;
        }
        return String.format(Locale.ROOT, "%02d/%02d/%04d", day, month, year);
    }

    private String normalizeSplitDate(String value) {
        String[] parts = value.split("[/-]");
        if (parts.length != 3) {
            return null;
        }
        try {
            int day = Integer.parseInt(parts[0].trim());
            int month = Integer.parseInt(parts[1].trim());
            int year = Integer.parseInt(parts[2].trim());
            if (year < 100) {
                year += 2000;
            }
            if (day < 1 || day > 31 || month < 1 || month > 12 || year < 1900 || year > 2100) {
                return null;
            }
            return String.format(Locale.ROOT, "%02d/%02d/%04d", day, month, year);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String toEightDigitsDate(String digits) {
        if (digits == null || digits.isBlank()) {
            return null;
        }
        if (digits.length() == 8) {
            return digits;
        }
        // Common OCR noise: dd1mm1yyyy -> remove separators recognized as '1'.
        if (digits.length() == 10 && digits.charAt(2) == '1' && digits.charAt(5) == '1') {
            StringBuilder sb = new StringBuilder(digits);
            sb.deleteCharAt(5);
            sb.deleteCharAt(2);
            return sb.toString();
        }
        if (digits.length() == 9 && digits.charAt(2) == '1') {
            StringBuilder sb = new StringBuilder(digits);
            sb.deleteCharAt(2);
            return sb.toString();
        }
        if (digits.length() > 8) {
            return digits.substring(0, 2) + digits.substring(2, 4) + digits.substring(digits.length() - 4);
        }
        return null;
    }

    private Double extractAmount(String text, String regex) {
        String raw = findFirst(text, regex);
        if (raw == null) {
            return null;
        }
        return parseAmount(raw);
    }

    private Map<String, Double> extractTotalsByLabel(String text) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }

        String[] lines = text.split("\\R");
        Deque<String> pending = new LinkedList<>();
        Set<String> pendingSet = new HashSet<>();

        for (String lineRaw : lines) {
            String line = lineRaw != null ? lineRaw.trim() : "";
            if (line.isBlank()) {
                continue;
            }

            String normalizedLine = normalizeOcrText(line).toUpperCase(Locale.ROOT)
                    .replace(" ", "")
                    .replace(".", "");

            List<String> labels = detectTotalLabels(normalizedLine);
            Double inlineAmount = parseAmount(line);

            if (!labels.isEmpty()) {
                for (String label : labels) {
                    boolean isLikelyRateLine = line.contains("%");
                    boolean acceptInlineForLabel = inlineAmount != null && (!isLikelyRateLine || !"tva".equals(label));
                    if (acceptInlineForLabel && !out.containsKey(label)) {
                        out.put(label, inlineAmount);
                    } else if (!out.containsKey(label) && pendingSet.add(label)) {
                        pending.addLast(label);
                    }
                }
                continue;
            }

            if (inlineAmount != null && !pending.isEmpty()) {
                String label = pending.removeFirst();
                pendingSet.remove(label);
                if (!out.containsKey(label)) {
                    out.put(label, inlineAmount);
                }
            }
        }

        // Regex fallback.
        if (!out.containsKey("amountTTC")) {
            Double ttc = extractAmount(text, "(?i)total\\s*t\\.?t\\.?c\\.?\\s*[:#-]?\\s*([\\d\\s,.]{3,15})");
            if (ttc != null) {
                out.put("amountTTC", ttc);
            }
        }
        if (!out.containsKey("amountHT")) {
            Double ht = extractAmount(text, "(?i)total\\s*h\\.?t\\.?\\s*[:#-]?\\s*([\\d\\s,.]{3,15})");
            if (ht != null) {
                out.put("amountHT", ht);
            }
        }
        if (!out.containsKey("tva")) {
            Double tva = extractAmount(text,
                    "(?i)total\\s*t\\.?v\\.?a\\.?\\s*(?:\\d{1,2}\\s*%)?\\s*[:#-]?\\s*([\\d\\s,.]{2,15})");
            if (tva != null) {
                out.put("tva", tva);
            }
        }

        return out;
    }

    private List<String> detectTotalLabels(String normalizedLine) {
        List<String> labels = new ArrayList<>();
        if (normalizedLine.contains("TOTALTTC")) {
            labels.add("amountTTC");
        }
        if (normalizedLine.contains("TOTALHT") || normalizedLine.contains("TOTALHORS")) {
            labels.add("amountHT");
        }
        if (normalizedLine.contains("TOTALTVA") || normalizedLine.contains("TVA20%")
                || normalizedLine.contains("TVA20")) {
            labels.add("tva");
        }
        return labels;
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
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String normalizeOcrText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('’', '\'')
                .replace('`', '\'')
                .replace('“', '"')
                .replace('”', '"');
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().replace(",", "."));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
