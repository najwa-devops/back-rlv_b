package com.example.releve_bancaire.entity.dynamic;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "field_patterns")
@Data
public class FieldPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Column(name = "pattern_regex", nullable = false, length = 500)
    private String patternRegex;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "description", length = 200)
    private String description;
}
