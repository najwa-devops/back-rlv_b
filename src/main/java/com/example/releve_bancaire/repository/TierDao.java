package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.entity.account_tier.Tier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository pour l'entité Tier
 * VERSION AVEC MODE AUXILIAIRE - SANS supplierAccount
 */
@Repository
public interface TierDao extends JpaRepository<Tier, Long> {

        // ===================== RECHERCHE PAR CHAMPS UNIQUES =====================

        /**
         * Recherche un tier par son numéro de tier
         */
        Optional<Tier> findByIdAndDossierId(Long id, Long dossierId);

        Optional<Tier> findByDossierIdAndTierNumber(Long dossierId, String tierNumber);

        boolean existsByDossierIdAndTierNumber(Long dossierId, String tierNumber);

        /**
         * Recherche un tier par compte collectif + numéro (mode auxiliaire)
         */
        Optional<Tier> findByDossierIdAndCollectifAccountAndTierNumber(Long dossierId, String collectifAccount,
                        String tierNumber);

        /**
         * Recherche un tier par ICE
         */
        Optional<Tier> findByDossierIdAndIce(Long dossierId, String ice);

        boolean existsByDossierIdAndIce(Long dossierId, String ice);

        /**
         * Recherche un tier par IF
         */
        Optional<Tier> findByDossierIdAndIfNumber(Long dossierId, String ifNumber);

        boolean existsByDossierIdAndIfNumber(Long dossierId, String ifNumber);

        // ===================== LISTES =====================

        /**
         * Liste de tous les tiers actifs triés par libellé
         */
        List<Tier> findByDossierIdAndActiveTrueOrderByLibelleAsc(Long dossierId);

        /**
         * Liste de tous les tiers triés par libellé
         */
        List<Tier> findByDossierIdOrderByLibelleAsc(Long dossierId);

        /**
         * Liste des tiers par mode auxiliaire
         */
        List<Tier> findByDossierIdAndAuxiliaireMode(Long dossierId, Boolean auxiliaireMode);

        // ===================== RECHERCHE MULTI-CRITÈRES =====================

        /**
         * Recherche par libellé, ICE, IF, tierNumber, collectifAccount
         */
        @Query("SELECT t FROM Tier t WHERE t.dossierId = :dossierId AND (" +
                        "LOWER(t.libelle) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
                        "t.ice LIKE CONCAT('%', :query, '%') OR " +
                        "t.ifNumber LIKE CONCAT('%', :query, '%') OR " +
                        "t.tierNumber LIKE CONCAT('%', :query, '%') OR " +
                        "t.collectifAccount LIKE CONCAT('%', :query, '%'))")
        List<Tier> search(@Param("dossierId") Long dossierId, @Param("query") String query);

        // ===================== FILTRES CONFIGURATION COMPTABLE =====================

        @Query("SELECT t FROM Tier t WHERE t.dossierId = :dossierId AND " +
                        "t.defaultChargeAccount IS NOT NULL AND " +
                        "t.tvaAccount IS NOT NULL")
        List<Tier> findWithAccountingConfiguration(@Param("dossierId") Long dossierId);

        @Query("SELECT t FROM Tier t WHERE t.dossierId = :dossierId AND (" +
                        "t.defaultChargeAccount IS NULL OR " +
                        "t.tvaAccount IS NULL)")
        List<Tier> findWithoutAccountingConfiguration(@Param("dossierId") Long dossierId);

        @Query("SELECT COUNT(t) FROM Tier t WHERE t.dossierId = :dossierId AND " +
                        "t.defaultChargeAccount IS NOT NULL AND " +
                        "t.tvaAccount IS NOT NULL")
        long countWithAccountingConfiguration(@Param("dossierId") Long dossierId);

        @Query("SELECT COUNT(t) FROM Tier t WHERE t.dossierId = :dossierId AND (" +
                        "t.defaultChargeAccount IS NULL OR " +
                        "t.tvaAccount IS NULL)")
        long countWithoutAccountingConfiguration(@Param("dossierId") Long dossierId);

        // ===================== FILTRES IDENTIFIANTS FISCAUX =====================

        @Query("SELECT t FROM Tier t WHERE t.dossierId = :dossierId AND t.ice IS NOT NULL AND t.ice != ''")
        List<Tier> findWithIce(@Param("dossierId") Long dossierId);

        @Query("SELECT t FROM Tier t WHERE t.dossierId = :dossierId AND t.ifNumber IS NOT NULL AND t.ifNumber != ''")
        List<Tier> findWithIfNumber(@Param("dossierId") Long dossierId);

        @Query("SELECT t FROM Tier t WHERE t.dossierId = :dossierId AND (" +
                        "(t.ice IS NULL OR t.ice = '') AND " +
                        "(t.ifNumber IS NULL OR t.ifNumber = ''))")
        List<Tier> findWithoutFiscalIdentifier(@Param("dossierId") Long dossierId);

        @Query("SELECT COUNT(t) FROM Tier t WHERE t.dossierId = :dossierId AND t.ice IS NOT NULL AND t.ice != ''")
        long countWithIce(@Param("dossierId") Long dossierId);

        @Query("SELECT COUNT(t) FROM Tier t WHERE t.dossierId = :dossierId AND t.ifNumber IS NOT NULL AND t.ifNumber != ''")
        long countWithIfNumber(@Param("dossierId") Long dossierId);

        // ===================== COMPTEURS =====================

        long countByDossierId(Long dossierId);

        long countByDossierIdAndActiveTrue(Long dossierId);

        long countByDossierIdAndAuxiliaireMode(Long dossierId, Boolean auxiliaireMode);
}
