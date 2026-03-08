package com.example.releve_bancaire.servises.patterns;

import com.example.releve_bancaire.entity.dynamic.FieldPattern;
import com.example.releve_bancaire.repository.FieldPatternDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FieldPatternService {
    private final FieldPatternDao fieldPatternDao;
    private final Map<String, List<Pattern>> patternCache = new ConcurrentHashMap<>();

    public List<Pattern> getPatternsForField(String fieldName) {
        return patternCache.computeIfAbsent(fieldName, this::loadPatternsForField);
    }

    public Optional<String> extractFirstMatch(String fieldName, String text) {
        if (fieldName == null || fieldName.isBlank() || text == null || text.isBlank()) {
            return Optional.empty();
        }

        List<Pattern> patterns = getPatternsForField(fieldName);
        if (patterns.isEmpty()) {
            // Self-heal cache if patterns were missing at first load.
            patternCache.remove(fieldName);
            patterns = getPatternsForField(fieldName);
        }

        for (Pattern pattern : patterns) {
            try {
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                    if (value != null && !value.isBlank()) {
                        return Optional.of(value.trim());
                    }
                }
            } catch (Exception e) {
                log.warn("Erreur extraction pattern [{}] pour champ [{}]: {}", pattern.pattern(), fieldName,
                        e.getMessage());
            }
        }

        return Optional.empty();
    }

    private List<Pattern> loadPatternsForField(String fieldName) {
        List<Pattern> patterns = fieldPatternDao.findByFieldNameAndActiveOrderByPriority(fieldName, true)
                .stream()
                .map(fp -> {
                    try {
                        return Pattern.compile(fp.getPatternRegex(), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
                    } catch (Exception e) {
                        log.error("Regex invalide ignorée [{}] : {}",
                                fp.getFieldName(),
                                fp.getPatternRegex(),
                                e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (patterns.isEmpty()) {
            log.warn("Aucun pattern actif charge pour le champ [{}]", fieldName);
        }
        return patterns;
    }

    public FieldPattern addPattern(FieldPattern pattern) {
        FieldPattern saved = fieldPatternDao.save(pattern);
        patternCache.remove(saved.getFieldName());
        return saved;
    }

    public FieldPattern updatePattern(Long id, FieldPattern updatedPattern) {
        FieldPattern existing = fieldPatternDao.findById(id)
                .orElseThrow(() -> new RuntimeException("Pattern non trouvé"));

        existing.setPatternRegex(updatedPattern.getPatternRegex());
        existing.setPriority(updatedPattern.getPriority());
        existing.setActive(updatedPattern.getActive());
        existing.setDescription(updatedPattern.getDescription());

        FieldPattern saved = fieldPatternDao.save(existing);
        patternCache.remove(saved.getFieldName());
        return saved;
    }

    public void deletePattern(Long id) {
        FieldPattern pattern = fieldPatternDao.findById(id)
                .orElseThrow(() -> new RuntimeException("Pattern non trouvé"));

        pattern.setActive(false); // Soft delete
        fieldPatternDao.save(pattern);
        patternCache.remove(pattern.getFieldName());
    }

    public List<FieldPattern> getAllActivePatterns() {
        return fieldPatternDao.findByActiveOrderByFieldNameAscPriorityAsc(true);
    }
}
