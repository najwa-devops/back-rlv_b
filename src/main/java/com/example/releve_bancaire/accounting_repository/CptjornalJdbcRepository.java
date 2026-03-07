package com.example.releve_bancaire.accounting_repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class CptjornalJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public long findMaxNumero() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(numero), 0) FROM cptjornal",
                Long.class);
        return value == null ? 0L : value;
    }

    public void insertAll(List<CptjornalRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO cptjornal
                    (numero, ndosjrn, nmois, mois, ncompt, ecriture, debit, credit, valider, datcompl, dat, mnt_rester)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                CptjornalRow row = rows.get(i);
                ps.setLong(1, row.numero());
                ps.setString(2, row.ndosjrn());
                ps.setInt(3, row.nmois());
                ps.setString(4, row.mois());
                ps.setString(5, row.ncompt());
                ps.setString(6, row.ecriture());
                ps.setBigDecimal(7, row.debit());
                ps.setBigDecimal(8, row.credit());
                ps.setString(9, row.valider());
                ps.setObject(10, row.datcompl());
                ps.setObject(11, row.dat());
                if (row.mntRester() == null) {
                    ps.setNull(12, Types.DECIMAL);
                } else {
                    ps.setBigDecimal(12, row.mntRester());
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public record CptjornalRow(
            long numero,
            String ndosjrn,
            int nmois,
            String mois,
            String ncompt,
            String ecriture,
            BigDecimal debit,
            BigDecimal credit,
            String valider,
            LocalDate datcompl,
            LocalDate dat,
            BigDecimal mntRester) {
    }
}
