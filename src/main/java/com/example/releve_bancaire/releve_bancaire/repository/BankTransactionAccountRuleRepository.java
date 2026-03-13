package com.example.releve_bancaire.releve_bancaire.repository;

import com.example.releve_bancaire.releve_bancaire.entity.BankTransactionAccountRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankTransactionAccountRuleRepository extends JpaRepository<BankTransactionAccountRule, Long> {

    Optional<BankTransactionAccountRule> findByNormalizedLibelle(String normalizedLibelle);

    List<BankTransactionAccountRule> findTop200ByOrderByUsageCountDescUpdatedAtDesc();
}
