package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.entity.auth.Dossier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DossierDao extends JpaRepository<Dossier, Long> {
    List<Dossier> findByComptableId(Long comptableId);

    List<Dossier> findByClientId(Long clientId);

    Optional<Dossier> findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(Long clientId);

    Optional<Dossier> findByIdAndComptableId(Long id, Long comptableId);

    Optional<Dossier> findByIdAndClientId(Long id, Long clientId);
}
