package com.example.releve_bancaire.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountingSupportTablesMigration {

    private final JdbcTemplate jdbcTemplate;

    @Bean
    ApplicationRunner ensureAccountingSupportTablesExist() {
        return args -> {
            createCptjournalTableIfMissing();
            createCptjournalSyncTrackerTableIfMissing();
        };
    }

    private void createCptjournalTableIfMissing() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS Cptjournal (
                    Numero BIGINT NOT NULL,
                    ndosjrn VARCHAR(50) NOT NULL,
                    nmois INT NOT NULL,
                    Mois VARCHAR(20) NOT NULL,
                    ncompt VARCHAR(9) NOT NULL,
                    ecriture VARCHAR(1000) NOT NULL,
                    debit DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    credit DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    valider CHAR(1) NOT NULL DEFAULT '1',
                    datcompl DATE NOT NULL,
                    dat INT NOT NULL,
                    annee INT NOT NULL,
                    mnt_rester DECIMAL(15,2) NULL,
                    INDEX idx_cptjournal_numero (Numero),
                    INDEX idx_cptjournal_journal_mois (ndosjrn, nmois)
                )
                """);
        log.info("Verification table Cptjournal terminee.");
    }

    private void createCptjournalSyncTrackerTableIfMissing() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cptjournal_sync_tracker (
                    statement_id BIGINT NOT NULL PRIMARY KEY,
                    synced_at DATETIME NOT NULL
                )
                """);
        log.info("Verification table cptjournal_sync_tracker terminee.");
    }
}
