package com.example.releve_bancaire.banking_repository;

import com.example.releve_bancaire.banking_entity.AccountingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountingConfigRepository extends JpaRepository<AccountingConfig, Long> {

    @Query("select distinct c.banque from AccountingConfig c order by c.banque")
    List<String> findDistinctBanques();
}

