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
public class AccountsTableMigration {

    private final JdbcTemplate jdbcTemplate;

    @Bean
    ApplicationRunner ensureAccountsTableExists() {
        return args -> {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        code VARCHAR(20) NOT NULL,
                        libelle VARCHAR(200) NOT NULL,
                        classe INT NOT NULL,
                        tva_rate DOUBLE NULL,
                        active BIT(1) NOT NULL DEFAULT b'1',
                        created_at DATETIME NULL,
                        updated_at DATETIME NULL,
                        created_by VARCHAR(100) NULL,
                        updated_by VARCHAR(100) NULL,
                        UNIQUE KEY idx_account_code (code),
                        KEY idx_account_classe (classe),
                        KEY idx_account_libelle (libelle),
                        KEY idx_account_active (active)
                    )
                    """);
            log.info("Verification table accounts terminee.");
        };
    }
}
