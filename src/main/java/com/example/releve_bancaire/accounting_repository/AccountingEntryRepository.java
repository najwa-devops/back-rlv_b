package com.example.releve_bancaire.accounting_repository;

import com.example.releve_bancaire.accounting_entity.AccountingEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountingEntryRepository extends JpaRepository<AccountingEntry, Long> {

    long countBySourceStatementId(Long sourceStatementId);
    java.util.List<AccountingEntry> findBySourceStatementIdOrderByNumeroAscIdAsc(Long sourceStatementId);

    @Query("""
            SELECT COALESCE(MAX(e.numero), 0)
            FROM AccountingEntry e
            WHERE e.ndosjrn = :journal
              AND e.nmois = :month
            """)
    Long findMaxNumeroByJournalAndMonth(@Param("journal") String journal, @Param("month") Integer month);
}
