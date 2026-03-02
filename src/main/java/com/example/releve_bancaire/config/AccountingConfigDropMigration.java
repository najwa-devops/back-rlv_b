package com.example.releve_bancaire.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountingConfigDropMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void dropAccountingConfigTableIfExists() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) {
                return;
            }
            String normalizedProduct = product.toLowerCase(Locale.ROOT);
            if (!normalizedProduct.contains("mysql") && !normalizedProduct.contains("mariadb")) {
                return;
            }

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS accounting_config");
            }
            log.warn("Migration DB appliquée: table accounting_config supprimée.");
        } catch (Exception e) {
            log.warn("Migration DB non appliquée (drop accounting_config): {}", e.getMessage());
        }
    }
}
