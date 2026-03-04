package com.example.releve_bancaire.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountingEntryNumeroIndexMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void ensureNumeroIndexIsNotUnique() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) {
                return;
            }
            String normalizedProduct = product.toLowerCase(Locale.ROOT);
            if (!normalizedProduct.contains("mysql") && !normalizedProduct.contains("mariadb")) {
                return;
            }

            String schema = connection.getCatalog();
            String indexName = "idx_acc_entry_journal_month_numero";
            if (!indexExists(connection, schema, "accounting_entry", indexName)) {
                return;
            }

            if (!indexIsUnique(connection, schema, "accounting_entry", indexName)) {
                return;
            }

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE accounting_entry DROP INDEX " + indexName);
                stmt.execute("CREATE INDEX " + indexName + " ON accounting_entry (ndosjrn, nmois, numero)");
            }
            log.warn("Migration DB appliquée: index {} rendu non unique.", indexName);
        } catch (Exception e) {
            log.warn("Migration DB non appliquée (index numero accounting_entry): {}", e.getMessage());
        }
    }

    private boolean indexExists(Connection connection, String schema, String table, String indexName) throws Exception {
        String sql = """
                SELECT 1
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = ?
                  AND index_name = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean indexIsUnique(Connection connection, String schema, String table, String indexName) throws Exception {
        String sql = """
                SELECT non_unique
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = ?
                  AND index_name = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getInt("non_unique") == 0;
            }
        }
    }
}

