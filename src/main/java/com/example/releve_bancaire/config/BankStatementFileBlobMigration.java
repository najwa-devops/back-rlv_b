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
public class BankStatementFileBlobMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void addBlobColumnsIfMissing() {
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
            if (!columnExists(connection, schema, "bank_statement", "file_content_type")) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ALTER TABLE bank_statement ADD COLUMN file_content_type VARCHAR(120) NULL");
                }
                log.info("Migration DB appliquée: colonne bank_statement.file_content_type ajoutée.");
            }

            if (!columnExists(connection, schema, "bank_statement", "file_data")) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ALTER TABLE bank_statement ADD COLUMN file_data LONGBLOB NULL");
                }
                log.info("Migration DB appliquée: colonne bank_statement.file_data ajoutée.");
            }
        } catch (Exception e) {
            log.warn("Migration DB non appliquée (colonnes BLOB relevés): {}", e.getMessage());
        }
    }

    private boolean columnExists(Connection connection, String schema, String table, String column) throws Exception {
        String sql = """
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_name = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
