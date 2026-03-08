package com.example.releve_bancaire.utils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogHelper {

    private static final String PREFIX_INVOICE = "[INVOICE]";
    private static final String PREFIX_OCR = "[OCR]";
    private static final String PREFIX_TEMPLATE = "[TEMPLATE]";
    private static final String PREFIX_TIER = "[TIER]";
    private static final String PREFIX_EXTRACTION = "[EXTRACTION]";
    private static final String PREFIX_VALIDATION = "[VALIDATION]";
    private static final String PREFIX_LEARNING = "[LEARNING]";
    private static final String PREFIX_DB = "[DATABASE]";

    // ===================== INVOICE LOGS =====================

    public static void logInvoiceUploadStart(String filename, long size) {
        log.info("{} Upload started: filename='{}', size={}KB",
                PREFIX_INVOICE, filename, size / 1024);
    }

    public static void logInvoiceCreated(Long id, String status) {
        log.info("{} Created: id={}, status={}", PREFIX_INVOICE, id, status);
    }

    public static void logInvoiceProcessingStart(Long id) {
        log.info("{} Processing started: id={}", PREFIX_INVOICE, id);
    }

    public static void logInvoiceProcessingEnd(Long id, String status, long durationMs) {
        log.info("{} Processing completed: id={}, status={}, duration={}ms",
                PREFIX_INVOICE, id, status, durationMs);
    }

    public static void logInvoiceProcessingError(Long id, String stage, String error) {
        log.error("{} Processing failed: id={}, stage='{}', error='{}'",
                PREFIX_INVOICE, id, stage, error);
    }

    // ===================== OCR LOGS =====================

    public static void logOcrStart(String filename) {
        log.info("{} Starting OCR extraction: filename='{}'", PREFIX_OCR, filename);
    }

    public static void logOcrSuccess(int length, double confidence, long durationMs, int attemptNumber) {
        log.info("{} Extraction successful: length={} chars, confidence={}%, duration={}ms, attempt={}",
                PREFIX_OCR, length, confidence, durationMs, attemptNumber);
    }

    public static void logOcrLowConfidence(double confidence, double threshold) {
        log.warn("{} Low confidence detected: actual={}%, threshold={}%",
                PREFIX_OCR, confidence, threshold);
    }

    public static void logOcrFailed(String filename, String error) {
        log.error("{} Extraction failed: filename='{}', error='{}'",
                PREFIX_OCR, filename, error);
    }

    // ===================== TEMPLATE LOGS =====================

    public static void logTemplateDetectionStart(String signatureType, String signatureValue) {
        log.info("{} Detection started: signature={}:'{}'",
                PREFIX_TEMPLATE, signatureType, signatureValue);
    }

    public static void logTemplateFound(Long id, String name, String signatureType, String signatureValue) {
        log.info("{} Matched: id={}, name='{}', signature={}:'{}'",
                PREFIX_TEMPLATE, id, name, signatureType, signatureValue);
    }

    public static void logTemplateNotFound(String signatureType, String signatureValue) {
        log.warn("{} Not found: signature={}:'{}' - Manual creation required",
                PREFIX_TEMPLATE, signatureType, signatureValue);
    }

    public static void logTemplateCreated(Long id, String name, String signatureType, String signatureValue) {
        log.info("{} Created: id={}, name='{}', signature={}:'{}'",
                PREFIX_TEMPLATE, id, name, signatureType, signatureValue);
    }

    // ===================== EXTRACTION LOGS =====================

    public static void logExtractionStart(boolean hasTemplate, Long templateId) {
        if (hasTemplate) {
            log.info("{} Starting with template: templateId={}", PREFIX_EXTRACTION, templateId);
        } else {
            log.info("{} Starting without template: using default patterns", PREFIX_EXTRACTION);
        }
    }

    public static void logExtractionComplete(int extracted, int missing, double confidence) {
        log.info("{} Completed: extracted={} fields, missing={} fields, confidence={}%",
                PREFIX_EXTRACTION, extracted, missing, Math.round(confidence));
    }

    public static void logFieldExtracted(String fieldName, String value, double confidence) {
        log.debug("{} Field found: name='{}', value='{}', confidence={}%",
                PREFIX_EXTRACTION, fieldName, truncate(value, 50), confidence);
    }

    public static void logFieldMissing(String fieldName, boolean required) {
        if (required) {
            log.warn("{} Required field missing: name='{}'", PREFIX_EXTRACTION, fieldName);
        } else {
            log.debug("{} Optional field missing: name='{}'", PREFIX_EXTRACTION, fieldName);
        }
    }

    // ===================== TIER LOGS =====================

    public static void logTierSearchStart(String ifNumber, String ice) {
        log.info("{} Search started: IF='{}', ICE='{}'",
                PREFIX_TIER, maskValue(ifNumber), maskValue(ice));
    }

    public static void logTierFound(Long id, String name, String matchedBy, String value) {
        log.info("{} Found: id={}, name='{}', matchedBy={}, value='{}'",
                PREFIX_TIER, id, name, matchedBy, maskValue(value));
    }

    public static void logTierNotFound(String ifNumber, String ice) {
        log.warn("{} Not found: IF='{}', ICE='{}' - Manual linking required",
                PREFIX_TIER, maskValue(ifNumber), maskValue(ice));
    }

    public static void logTierLinked(Long invoiceId, Long tierId, String tierName) {
        log.info("{} Linked to invoice: invoiceId={}, tierId={}, tierName='{}'",
                PREFIX_TIER, invoiceId, tierId, tierName);
    }

    // ===================== VALIDATION LOGS =====================

    public static void logAmountCalculated(String field, double value) {
        log.info("{} Amount calculated: field='{}', value={} DH",
                PREFIX_VALIDATION, field, value);
    }

    public static void logAmountMismatch(String field, double extracted, double calculated, double diff) {
        log.warn("{} Amount mismatch: field='{}', extracted={} DH, calculated={} DH, diff={} DH",
                PREFIX_VALIDATION, field, extracted, calculated, diff);
    }

    public static void logValidationSuccess(Long invoiceId) {
        log.info("{} Validation successful: invoiceId={}", PREFIX_VALIDATION, invoiceId);
    }

    public static void logValidationFailed(Long invoiceId, String reason) {
        log.warn("{} Validation failed: invoiceId={}, reason='{}'",
                PREFIX_VALIDATION, invoiceId, reason);
    }

    // ===================== LEARNING LOGS =====================

    public static void logPatternSaved(String fieldName, String pattern, Long invoiceId) {
        log.info("{} Pattern saved: field='{}', pattern='{}', invoiceId={}",
                PREFIX_LEARNING, fieldName, pattern, invoiceId);
    }

    public static void logPatternApproved(Long learningId, String pattern, String approvedBy) {
        log.info("{} Pattern approved: id={}, pattern='{}', approvedBy='{}'",
                PREFIX_LEARNING, learningId, pattern, approvedBy);
    }

    public static void logPatternIntegrated(Long learningId, Long templateId, String fieldName) {
        log.info("{} Pattern integrated: learningId={}, templateId={}, field='{}'",
                PREFIX_LEARNING, learningId, templateId, fieldName);
    }

    // ===================== DATABASE LOGS =====================

    public static void logDbSave(String entity, Long id) {
        log.debug("{} Saved: entity='{}', id={}", PREFIX_DB, entity, id);
    }

    public static void logDbUpdate(String entity, Long id) {
        log.debug("{} Updated: entity='{}', id={}", PREFIX_DB, entity, id);
    }

    public static void logDbError(String operation, String entity, String error) {
        log.error("{} Operation failed: operation='{}', entity='{}', error='{}'",
                PREFIX_DB, operation, entity, error);
    }

    // ===================== HELPERS =====================

    private static String truncate(String value, int maxLength) {
        if (value == null) return "null";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private static String maskValue(String value) {
        if (value == null || value.isBlank()) return "N/A";
        if (value.length() <= 4) return "***";
        return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
    }
}
