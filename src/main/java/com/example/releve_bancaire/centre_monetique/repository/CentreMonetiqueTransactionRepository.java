package com.example.releve_bancaire.centre_monetique.repository;

import com.example.releve_bancaire.centre_monetique.entity.CentreMonetiqueTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CentreMonetiqueTransactionRepository extends JpaRepository<CentreMonetiqueTransaction, Long> {
    List<CentreMonetiqueTransaction> findByBatchIdOrderByRowIndexAsc(Long batchId);
    void deleteByBatchId(Long batchId);
}
