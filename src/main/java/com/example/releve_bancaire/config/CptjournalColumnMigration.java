package com.example.releve_bancaire.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CptjournalColumnMigration {

    private final JdbcTemplate jdbcTemplate;

    @org.springframework.context.annotation.Bean
    ApplicationRunner migrateCptjournalColumns() {
        return args -> {
            if (!tableExists("Cptjournal")) {
                return;
            }

            renameColumnIfExists("ncompte", "ncompt", "VARCHAR(9) NOT NULL");
            renameColumnIfExists("datecompl", "datcompl", "DATE NOT NULL");
            renameColumnIfExists("date", "dat", "INT NOT NULL");
        };
    }

    private void renameColumnIfExists(String oldName, String newName, String typeDefinition) {
        if (!columnExists(oldName) || columnExists(newName)) {
            return;
        }
        String sql = "ALTER TABLE Cptjournal CHANGE COLUMN " + oldName + " " + newName + " " + typeDefinition;
        jdbcTemplate.execute(sql);
        log.info("Cptjournal migration: colonne {} renommee en {}", oldName, newName);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'Cptjournal' AND column_name = ?",
                Integer.class,
                columnName);
        return count != null && count > 0;
    }
}
