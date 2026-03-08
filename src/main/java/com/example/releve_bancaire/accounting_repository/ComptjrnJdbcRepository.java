package com.example.releve_bancaire.accounting_repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ComptjrnJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<String> findCodejrnByCdcmptctr(String accountCode) {
        if (accountCode == null || accountCode.isBlank()) {
            return Optional.empty();
        }

        List<String> results = jdbcTemplate.query(
                """
                        SELECT TRIM(codejrn)
                        FROM comptjrn
                        WHERE TRIM(cdcmptctr) = ?
                          AND codejrn IS NOT NULL
                          AND TRIM(codejrn) <> ''
                        ORDER BY nummouv DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getString(1),
                accountCode.trim());

        return results.stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .map(String::trim);
    }
}
