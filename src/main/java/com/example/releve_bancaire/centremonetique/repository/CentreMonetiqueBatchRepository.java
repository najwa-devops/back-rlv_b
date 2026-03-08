package com.example.releve_bancaire.centremonetique.repository;

import com.example.releve_bancaire.centremonetique.entity.CentreMonetiqueBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CentreMonetiqueBatchRepository extends JpaRepository<CentreMonetiqueBatch, Long> {
    List<CentreMonetiqueBatch> findTop200ByOrderByCreatedAtDesc();
}
