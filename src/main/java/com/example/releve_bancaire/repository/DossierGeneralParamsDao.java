package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.entity.auth.DossierGeneralParams;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DossierGeneralParamsDao extends JpaRepository<DossierGeneralParams, Long> {
    Optional<DossierGeneralParams> findByDossierId(Long dossierId);
}
