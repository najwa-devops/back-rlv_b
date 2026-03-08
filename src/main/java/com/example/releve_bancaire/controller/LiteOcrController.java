package com.example.releve_bancaire.controller;

import com.example.releve_bancaire.entity.dynamic.DynamicInvoice;
import com.example.releve_bancaire.entity.invoice.InvoiceStatus;
import com.example.releve_bancaire.repository.DynamicInvoiceDao;
import com.example.releve_bancaire.servises.dynamic.DynamicInvoiceProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/lite/invoices")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class LiteOcrController {

    private final DynamicInvoiceProcessingService processingService;
    private final DynamicInvoiceDao dynamicInvoiceDao;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadAndProcess(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "empty_file"));
            }

            DynamicInvoice invoice = processingService.processInvoice(file, null);
            return ResponseEntity.ok(toResponse(invoice, false));
        } catch (Exception e) {
            log.error("Lite OCR upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        Stream<DynamicInvoice> stream = dynamicInvoiceDao.findAll().stream();

        if (status != null && !status.isBlank()) {
            try {
                InvoiceStatus invoiceStatus = InvoiceStatus.valueOf(status.toUpperCase());
                stream = stream.filter(i -> i.getStatus() == invoiceStatus);
            } catch (IllegalArgumentException ignored) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid_status"));
            }
        }

        List<Map<String, Object>> invoices = stream
                .sorted(Comparator
                        .comparing(DynamicInvoice::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .limit(Math.max(1, limit))
                .map(i -> toResponse(i, false))
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", invoices.size(),
                "invoices", invoices));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return dynamicInvoiceDao.findById(id)
                .map(invoice -> ResponseEntity.ok(toResponse(invoice, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toResponse(DynamicInvoice invoice, boolean includeRawText) {
        Map<String, Object> fields = invoice.getFieldsData() != null ? invoice.getFieldsData() : Map.of();

        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", invoice.getId());
        body.put("filename", invoice.getFilename());
        body.put("originalName", invoice.getOriginalName());
        body.put("status", invoice.getStatus() != null ? invoice.getStatus().name() : null);
        body.put("templateId", invoice.getTemplateId());
        body.put("templateName", invoice.getTemplateName());
        body.put("overallConfidence", invoice.getOverallConfidence());
        body.put("fieldsData", fields);
        body.put("invoiceNumber", invoice.getInvoiceNumber());
        body.put("supplier", invoice.getSupplier());
        body.put("invoiceDate", invoice.getInvoiceDate());
        body.put("ice", invoice.getIce());
        body.put("ifNumber", invoice.getIfNumber());
        body.put("rcNumber", invoice.getRcNumber());
        body.put("amountHT", invoice.getAmountHT());
        body.put("tva", invoice.getTva());
        body.put("amountTTC", invoice.getAmountTTC());
        body.put("createdAt", invoice.getCreatedAt());
        body.put("updatedAt", invoice.getUpdatedAt());

        if (includeRawText) {
            body.put("rawOcrText", invoice.getRawOcrText());
            body.put("missingFields", invoice.getMissingFields() != null ? invoice.getMissingFields() : List.of());
            body.put("lowConfidenceFields",
                    invoice.getLowConfidenceFields() != null ? invoice.getLowConfidenceFields() : List.of());
            body.put("autoFilledFields",
                    invoice.getAutoFilledFields() != null ? invoice.getAutoFilledFields() : List.of());
            body.put("filePath", invoice.getFilePath());
            body.put("fileSize", invoice.getFileSize());
        }

        return body;
    }
}
