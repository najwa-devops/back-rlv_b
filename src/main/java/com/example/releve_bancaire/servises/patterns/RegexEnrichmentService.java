package com.example.releve_bancaire.servises.patterns;

import com.example.releve_bancaire.entity.dynamic.DynamicInvoice;
import com.example.releve_bancaire.entity.dynamic.FieldLearningData;
import com.example.releve_bancaire.entity.dynamic.FieldPattern;
import com.example.releve_bancaire.entity.dynamic.LearningStatus;
import com.example.releve_bancaire.evo.RegexGenerator;
import com.example.releve_bancaire.evo.RegexResult;
import com.example.releve_bancaire.repository.FieldLearningDataDao;
import com.example.releve_bancaire.repository.FieldPatternDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RegexEnrichmentService {

    private final FieldLearningDataDao learningDataDao;
    private final FieldPatternDao fieldPatternDao;

    @Value("${regex.enrichment.enabled:false}")
    private boolean enabled;

    @Value("${regex.enrichment.min-samples:3}")
    private int minSamples;

    @Value("${regex.enrichment.min-confidence:0.75}")
    private double minConfidence;

    @Value("${regex.enrichment.max-history-samples:20}")
    private int maxHistorySamples;

    public Map<String, RegexResult> generateAndStoreRegex(
            DynamicInvoice invoice,
            Map<String, Object> fieldsData,
            Collection<String> targetFields) {
        if (!enabled) {
            return Collections.emptyMap();
        }
        if (invoice == null || fieldsData == null || fieldsData.isEmpty() || targetFields == null
                || targetFields.isEmpty()) {
            return Collections.emptyMap();
        }

        String supplierIce = firstNonBlank(
                asString(fieldsData.get("ice")),
                asString(invoice.getFieldsData() != null ? invoice.getFieldsData().get("ice") : null),
                invoice.getIce());
        String supplierIf = firstNonBlank(
                asString(fieldsData.get("ifNumber")),
                asString(invoice.getFieldsData() != null ? invoice.getFieldsData().get("ifNumber") : null),
                invoice.getIfNumber());

        Map<String, RegexResult> generated = new LinkedHashMap<>();

        for (String fieldName : targetFields) {
            String currentValue = asString(fieldsData.get(fieldName));
            if (currentValue == null || currentValue.isBlank()) {
                continue;
            }

            List<String> samples = collectSamples(fieldName, currentValue, supplierIce, supplierIf);
            if (samples.size() < minSamples) {
                log.debug("Regex learning skipped for field '{}' (samples={}/{})", fieldName, samples.size(),
                        minSamples);
                continue;
            }

            RegexResult result = RegexGenerator.builder()
                    .learn()
                    .addSamples(samples)
                    .build();

            if (result.regex() == null || result.regex().isBlank() || result.confidence() < minConfidence) {
                log.debug("Generated regex ignored for field '{}' (confidence={})", fieldName, result.confidence());
                continue;
            }

            if (!isValidRegex(result.regex())) {
                log.warn("Generated invalid regex ignored for field '{}': {}", fieldName, result.regex());
                continue;
            }

            upsertFieldPattern(fieldName, result.regex(), supplierIce, supplierIf, result.confidence());
            generated.put(fieldName, result);
        }

        return generated;
    }

    private List<String> collectSamples(String fieldName, String currentValue, String supplierIce, String supplierIf) {
        Set<String> values = new LinkedHashSet<>();
        values.add(normalize(currentValue));

        List<LearningStatus> statuses = List.of(LearningStatus.APPROVED, LearningStatus.AUTO_APPROVED);
        List<FieldLearningData> history;

        if (supplierIce != null && !supplierIce.isBlank()) {
            history = learningDataDao.findByFieldNameAndSupplierIceAndStatusInOrderByCreatedAtDesc(fieldName,
                    supplierIce, statuses);
        } else if (supplierIf != null && !supplierIf.isBlank()) {
            history = learningDataDao.findByFieldNameAndSupplierIfAndStatusInOrderByCreatedAtDesc(fieldName, supplierIf,
                    statuses);
        } else {
            history = learningDataDao.findByFieldNameAndStatusInOrderByCreatedAtDesc(fieldName, statuses);
        }

        history.stream()
                .map(FieldLearningData::getFieldValue)
                .filter(Objects::nonNull)
                .map(this::normalize)
                .filter(v -> !v.isBlank())
                .limit(Math.max(1, maxHistorySamples))
                .forEach(values::add);

        return values.stream().filter(v -> !v.isBlank()).collect(Collectors.toList());
    }

    private void upsertFieldPattern(String fieldName, String regex, String supplierIce, String supplierIf,
            double confidence) {
        String scope = buildScope(fieldName, supplierIce, supplierIf);
        Optional<FieldPattern> existing = fieldPatternDao
                .findFirstByFieldNameAndDescriptionAndActiveOrderByPriorityDesc(fieldName, scope, true);

        FieldPattern pattern = existing.orElseGet(FieldPattern::new);
        pattern.setFieldName(fieldName);
        pattern.setPatternRegex(regex);
        pattern.setPriority((int) Math.round(Math.max(1.0, confidence * 100.0)));
        pattern.setActive(true);
        pattern.setDescription(scope);

        fieldPatternDao.save(pattern);
        log.info("Learned regex saved for field '{}': {}", fieldName, regex);
    }

    private String buildScope(String fieldName, String supplierIce, String supplierIf) {
        String ice = supplierIce != null ? supplierIce : "";
        String ifNumber = supplierIf != null ? supplierIf : "";
        return "AUTO_REGEX|field=" + fieldName + "|ice=" + ice + "|if=" + ifNumber;
    }

    private boolean isValidRegex(String regex) {
        try {
            Pattern.compile(regex);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String asString(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
