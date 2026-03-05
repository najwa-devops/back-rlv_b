package com.example.releve_bancaire.controller.pattern;

import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.entity.dynamic.FieldPattern;
import com.example.releve_bancaire.servises.patterns.FieldPatternService;
import com.example.releve_bancaire.security.RequireRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/field-patterns")
@CrossOrigin("*")
@RequiredArgsConstructor
@RequireRole({ UserRole.ADMIN })
public class FieldPatternController {

    private final FieldPatternService fieldPatternService;

    @GetMapping
    public List<FieldPattern> getAllPatterns() {
        return fieldPatternService.getAllActivePatterns();
    }

    @PostMapping
    public FieldPattern addPattern(@RequestBody FieldPattern pattern) {
        return fieldPatternService.addPattern(pattern);
    }

    @PutMapping("/{id}")
    public FieldPattern updatePattern(@PathVariable Long id, @RequestBody FieldPattern pattern) {
        return fieldPatternService.updatePattern(id, pattern);
    }

    @DeleteMapping("/{id}")
    public void deletePattern(@PathVariable Long id) {
        fieldPatternService.deletePattern(id);
    }
}
