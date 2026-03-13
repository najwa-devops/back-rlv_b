package com.example.releve_bancaire.centre_monetique.repository;

import com.example.releve_bancaire.centre_monetique.entity.CentreMonetiqueBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CentreMonetiqueBatchRepository extends JpaRepository<CentreMonetiqueBatch, Long> {
    List<CentreMonetiqueBatch> findTop200ByOrderByCreatedAtDesc();

    List<CentreMonetiqueBatch> findByRibAndStatusOrderByCreatedAtDesc(String rib, String status);
}
