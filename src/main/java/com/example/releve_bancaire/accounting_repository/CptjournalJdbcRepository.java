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
public class CptjournalJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public long findMaxNumero() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(Numero), 0) FROM Cptjournal",
                Long.class);
        return value == null ? 0L : value;
    }

    public void insertAll(List<CptjournalRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO Cptjournal
                    (Numero, ndosjrn, nmois, Mois, ncompte, ecriture, debit, credit, valider, datecompl, date, annee, mnt_rester)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                CptjournalRow row = rows.get(i);
                ps.setLong(1, row.numero());
                ps.setString(2, row.ndosjrn());
                ps.setInt(3, row.nmois());
                ps.setString(4, row.mois());
                ps.setString(5, row.ncompte());
                ps.setString(6, row.ecriture());
                ps.setBigDecimal(7, row.debit());
                ps.setBigDecimal(8, row.credit());
                ps.setString(9, row.valider());
                ps.setObject(10, row.datecompl());
                ps.setInt(11, row.date());
                ps.setInt(12, row.annee());
                if (row.mntRester() == null) {
                    ps.setNull(13, Types.DECIMAL);
                } else {
                    ps.setBigDecimal(13, row.mntRester());
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public record CptjournalRow(
            long numero,
            String ndosjrn,
            int nmois,
            String mois,
            String ncompte,
            String ecriture,
            BigDecimal debit,
            BigDecimal credit,
            String valider,
            LocalDate datecompl,
            int date,
            int annee,
            BigDecimal mntRester) {
    }
}
