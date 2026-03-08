package com.example.releve_bancaire.centremonetique.config;

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
public class CentreMonetiqueSchemaMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void ensureIndexes() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) {
                return;
            }
            String normalized = product.toLowerCase(Locale.ROOT);
            if (!normalized.contains("mysql") && !normalized.contains("mariadb")) {
                return;
            }

            String schema = connection.getCatalog();
            if (!tableExists(connection, schema, "cm_batch") || !tableExists(connection, schema, "cm_transaction")) {
                return;
            }

            addColumnIfMissing(connection, schema, "cm_batch", "statement_period", "ALTER TABLE cm_batch ADD COLUMN statement_period VARCHAR(30) NULL");
            addColumnIfMissing(connection, schema, "cm_batch", "structure", "ALTER TABLE cm_batch ADD COLUMN structure VARCHAR(30) NOT NULL DEFAULT 'AUTO'");
            addColumnIfMissing(connection, schema, "cm_batch", "total_commission_ht", "ALTER TABLE cm_batch ADD COLUMN total_commission_ht DECIMAL(15,2) NULL");
            addColumnIfMissing(connection, schema, "cm_batch", "total_tva_sur_commissions", "ALTER TABLE cm_batch ADD COLUMN total_tva_sur_commissions DECIMAL(15,2) NULL");
            addColumnIfMissing(connection, schema, "cm_batch", "solde_net_remise", "ALTER TABLE cm_batch ADD COLUMN solde_net_remise DECIMAL(15,2) NULL");
            addColumnIfMissing(connection, schema, "cm_transaction", "dc_flag", "ALTER TABLE cm_transaction ADD COLUMN dc_flag VARCHAR(2) NULL");
            widenVarcharColumnIfNeeded(connection, schema, "cm_transaction", "section", 64,
                    "ALTER TABLE cm_transaction MODIFY COLUMN section VARCHAR(64) NOT NULL");
            widenVarcharColumnIfNeeded(connection, schema, "cm_transaction", "date", 16,
                    "ALTER TABLE cm_transaction MODIFY COLUMN date VARCHAR(16) NULL");
            widenVarcharColumnIfNeeded(connection, schema, "cm_transaction", "reference", 32,
                    "ALTER TABLE cm_transaction MODIFY COLUMN reference VARCHAR(32) NULL");
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE cm_transaction MODIFY COLUMN debit DECIMAL(15,4) NULL");
            } catch (Exception ignored) {
                // Column may already have the expected precision/scale.
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE cm_transaction MODIFY COLUMN credit DECIMAL(15,4) NULL");
            } catch (Exception ignored) {
                // Column may already have the expected precision/scale.
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("UPDATE cm_batch SET structure='AUTO' WHERE structure IS NULL OR structure=''");
            }

            createIndexIfMissing(connection, schema, "cm_batch", "idx_cm_batch_created_at",
                    "CREATE INDEX idx_cm_batch_created_at ON cm_batch(created_at)");
            createIndexIfMissing(connection, schema, "cm_batch", "idx_cm_batch_status",
                    "CREATE INDEX idx_cm_batch_status ON cm_batch(status)");
            createIndexIfMissing(connection, schema, "cm_transaction", "idx_cm_tx_batch_id",
                    "CREATE INDEX idx_cm_tx_batch_id ON cm_transaction(batch_id)");
            createIndexIfMissing(connection, schema, "cm_transaction", "idx_cm_tx_batch_row",
                    "CREATE INDEX idx_cm_tx_batch_row ON cm_transaction(batch_id, row_index)");
        } catch (Exception e) {
            log.warn("Migration schema centre monetique non appliquee: {}", e.getMessage());
        }
    }

    private void createIndexIfMissing(Connection connection,
                                      String schema,
                                      String table,
                                      String indexName,
                                      String createSql) throws Exception {
        if (indexExists(connection, schema, table, indexName)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createSql);
            log.info("Index cree: {}", indexName);
        }
    }

    private void addColumnIfMissing(Connection connection,
                                    String schema,
                                    String table,
                                    String columnName,
                                    String alterSql) throws Exception {
        if (columnExists(connection, schema, table, columnName)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(alterSql);
            log.info("Colonne creee: {}.{}", table, columnName);
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

    private boolean tableExists(Connection connection, String schema, String table) throws Exception {
        String sql = """
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_name = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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

    private void widenVarcharColumnIfNeeded(Connection connection,
                                            String schema,
                                            String table,
                                            String column,
                                            int targetLength,
                                            String alterSql) throws Exception {
        Integer currentLength = varcharLength(connection, schema, table, column);
        if (currentLength == null || currentLength >= targetLength) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(alterSql);
            log.info("Colonne agrandie: {}.{} {} -> {}", table, column, currentLength, targetLength);
        }
    }

    private Integer varcharLength(Connection connection, String schema, String table, String column) throws Exception {
        String sql = """
                SELECT character_maximum_length
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
                if (!rs.next()) {
                    return null;
                }
                int value = rs.getInt(1);
                return rs.wasNull() ? null : value;
            }
        }
    }
}
