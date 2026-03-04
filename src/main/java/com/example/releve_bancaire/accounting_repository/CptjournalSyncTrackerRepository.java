package com.example.releve_bancaire.accounting_repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CptjournalSyncTrackerRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean isSynced(Long statementId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cptjournal_sync_tracker WHERE statement_id = ?",
                Integer.class,
                statementId);
        return count != null && count > 0;
    }

    public void markSynced(Long statementId) {
        jdbcTemplate.update("""
                INSERT INTO cptjournal_sync_tracker(statement_id, synced_at)
                VALUES (?, NOW())
                ON DUPLICATE KEY UPDATE synced_at = VALUES(synced_at)
                """, statementId);
    }
}
