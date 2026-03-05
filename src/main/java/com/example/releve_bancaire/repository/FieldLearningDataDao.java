package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.entity.dynamic.FieldLearningData;
import com.example.releve_bancaire.entity.dynamic.LearningStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FieldLearningDataDao extends JpaRepository<FieldLearningData, Long> {

        // ===================== RECHERCHE PAR FACTURE =====================

        /**
         * Trouve tous les patterns d'une facture
         */
        List<FieldLearningData> findByInvoiceId(Long invoiceId);

        /**
         * Compte les patterns d'une facture
         */
        long countByInvoiceId(Long invoiceId);

        /**
         * Supprime les patterns d'apprentissage d'une facture
         */
        long deleteByInvoiceId(Long invoiceId);

        // ===================== RECHERCHE PAR STATUT =====================

        /**
         * Trouve tous les patterns en attente
         */
        List<FieldLearningData> findByStatusOrderByCreatedAtDesc(LearningStatus status);

        /**
         * Compte les patterns par statut
         */
        long countByStatus(LearningStatus status);

        /**
         * Trouve les patterns en attente avec haute confiance
         */
        @Query("SELECT f FROM FieldLearningData f WHERE f.status = 'PENDING' AND f.confidenceScore >= :minConfidence")
        List<FieldLearningData> findHighConfidencePending(@Param("minConfidence") Double minConfidence);

        // ===================== RECHERCHE PAR FOURNISSEUR =====================

        /**
         * Trouve tous les patterns d'un fournisseur (par ICE)
         */
        List<FieldLearningData> findBySupplierIceAndStatusOrderByCreatedAtDesc(
                        String supplierIce,
                        LearningStatus status);

        /**
         * Trouve tous les patterns d'un fournisseur (par IF)
         */
        List<FieldLearningData> findBySupplierIfAndStatusOrderByCreatedAtDesc(
                        String supplierIf,
                        LearningStatus status);

        // ===================== RECHERCHE PAR CHAMP =====================

        /**
         * Trouve tous les patterns d'un champ spÃ©cifique
         */
        List<FieldLearningData> findByFieldNameAndStatusOrderByOccurrenceCountDesc(
                        String fieldName,
                        LearningStatus status);

        List<FieldLearningData> findByFieldNameAndStatusInOrderByCreatedAtDesc(
                        String fieldName,
                        List<LearningStatus> statuses);

        List<FieldLearningData> findByFieldNameAndSupplierIceAndStatusInOrderByCreatedAtDesc(
                        String fieldName,
                        String supplierIce,
                        List<LearningStatus> statuses);

        List<FieldLearningData> findByFieldNameAndSupplierIfAndStatusInOrderByCreatedAtDesc(
                        String fieldName,
                        String supplierIf,
                        List<LearningStatus> statuses);

        /**
         * Trouve les patterns d'un champ pour un fournisseur spÃ©cifique
         */
        @Query("SELECT f FROM FieldLearningData f WHERE " +
                        "f.fieldName = :fieldName AND " +
                        "f.supplierIce = :supplierIce AND " +
                        "f.status = :status " +
                        "ORDER BY f.occurrenceCount DESC")
        List<FieldLearningData> findByFieldAndSupplier(
                        @Param("fieldName") String fieldName,
                        @Param("supplierIce") String supplierIce,
                        @Param("status") LearningStatus status);

        // ===================== DÃ‰TECTION DE DOUBLONS =====================

        /**
         * Trouve un pattern existant par hash
         */
        Optional<FieldLearningData> findByPatternHash(String patternHash);

        /**
         * VÃ©rifie si un pattern existe dÃ©jÃ 
         */
        boolean existsByPatternHash(String patternHash);

        /**
         * Trouve les patterns similaires (mÃªme champ + pattern + fournisseur)
         */
        @Query("SELECT f FROM FieldLearningData f WHERE " +
                        "f.fieldName = :fieldName AND " +
                        "f.detectedPattern = :pattern AND " +
                        "f.supplierIce = :supplierIce")
        List<FieldLearningData> findSimilarPatterns(
                        @Param("fieldName") String fieldName,
                        @Param("pattern") String pattern,
                        @Param("supplierIce") String supplierIce);

        // ===================== INTÃ‰GRATION DANS TEMPLATES =====================

        /**
         * Trouve les patterns approuvÃ©s non encore appliquÃ©s
         */
        @Query("SELECT f FROM FieldLearningData f WHERE " +
                        "f.status IN ('APPROVED', 'AUTO_APPROVED') AND " +
                        "f.appliedToTemplateId IS NULL " +
                        "ORDER BY f.occurrenceCount DESC, f.confidenceScore DESC")
        List<FieldLearningData> findReadyForIntegration();

        /**
         * Trouve les patterns appliquÃ©s Ã  un template
         */
        List<FieldLearningData> findByAppliedToTemplateId(Long templateId);

        // ===================== STATISTIQUES =====================

        /**
         * Patterns les plus frÃ©quents pour un champ
         */
        @Query("SELECT f.detectedPattern, COUNT(f) as count " +
                        "FROM FieldLearningData f " +
                        "WHERE f.fieldName = :fieldName AND f.status = 'APPROVED' " +
                        "GROUP BY f.detectedPattern " +
                        "ORDER BY count DESC")
        List<Object[]> findMostFrequentPatterns(@Param("fieldName") String fieldName);

        /**
         * Taux de rÃ©ussite par pattern
         */
        @Query("SELECT f.detectedPattern, " +
                        "AVG(f.confidenceScore) as avgConfidence, " +
                        "COUNT(f) as totalCount " +
                        "FROM FieldLearningData f " +
                        "WHERE f.fieldName = :fieldName " +
                        "GROUP BY f.detectedPattern " +
                        "ORDER BY avgConfidence DESC")
        List<Object[]> getPatternSuccessRate(@Param("fieldName") String fieldName);

        /**
         * Compte total par zone de document
         */
        @Query("SELECT f.documentZone, COUNT(f) FROM FieldLearningData f GROUP BY f.documentZone")
        List<Object[]> countByDocumentZone();

        // ===================== NETTOYAGE =====================

        /**
         * Supprime les patterns rejetÃ©s plus anciens que N jours
         * Utilise nativeQuery car JPQL ne supporte pas INTERVAL
         */
        @Modifying
        @Query(value = "DELETE FROM field_learning_data WHERE " +
                        "status = 'REJECTED' AND " +
                        "created_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL :days DAY)", nativeQuery = true)
        void deleteOldRejected(@Param("days") int days);

        /**
         * Supprime les patterns en attente non traitÃ©s depuis N jours
         * Utilise nativeQuery car JPQL ne supporte pas INTERVAL
         */
        @Modifying
        @Query(value = "DELETE FROM field_learning_data WHERE " +
                        "status = 'PENDING' AND " +
                        "created_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL :days DAY)", nativeQuery = true)
        void deleteOldPending(@Param("days") int days);

}
