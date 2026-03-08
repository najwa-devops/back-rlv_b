package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.entity.dynamic.FieldPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FieldPatternDao extends JpaRepository<FieldPattern, Long> {

    List<FieldPattern> findByFieldNameAndActiveOrderByPriority(String fieldName, Boolean active);

    List<FieldPattern> findByActiveOrderByFieldNameAscPriorityAsc(Boolean active);

    Optional<FieldPattern> findFirstByFieldNameAndDescriptionAndActiveOrderByPriorityDesc(
            String fieldName,
            String description,
            Boolean active);

}
