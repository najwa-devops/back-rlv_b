package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.entity.accounting.AccountingEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountingEntryDao extends JpaRepository<AccountingEntry, Long> {
    List<AccountingEntry> findByDossierIdOrderByEntryDateDescCreatedAtDesc(Long dossierId);

    List<AccountingEntry> findByInvoiceIdOrderByIdAsc(Long invoiceId);

    long deleteByInvoiceId(Long invoiceId);
}
