package com.example.releve_bancaire.banking_repository;

import com.example.releve_bancaire.banking_entity.BankStatement;
import com.example.releve_bancaire.banking_entity.ContinuityStatus;
import com.example.releve_bancaire.banking_entity.BankStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankStatementRepository extends JpaRepository<BankStatement, Long> {

    // ==================== RECHERCHE PAR RIB ====================

    List<BankStatement> findByRibOrderByYearDescMonthDesc(String rib);

    Page<BankStatement> findByRib(String rib, Pageable pageable);

    long countByRib(String rib);

    // ==================== RECHERCHE PAR PÉRIODE ====================

    List<BankStatement> findByYearAndMonthOrderByRib(Integer year, Integer month);

    List<BankStatement> findByYearOrderByMonthDesc(Integer year);

    Optional<BankStatement> findByRibAndYearAndMonth(String rib, Integer year, Integer month);
    List<BankStatement> findAllByRibAndYearAndMonthOrderByCreatedAtDescIdDesc(String rib, Integer year, Integer month);

    Optional<BankStatement> findByDuplicateHash(String duplicateHash);
    List<BankStatement> findAllByDuplicateHashOrderByCreatedAtDescIdDesc(String duplicateHash);

    // ==================== RECHERCHE PAR STATUT ====================

    List<BankStatement> findByStatusOrderByCreatedAtDesc(BankStatus status);

    Page<BankStatement> findByStatus(BankStatus status, Pageable pageable);

    long countByStatus(BankStatus status);

    List<BankStatement> findByContinuityStatusIn(List<ContinuityStatus> statuses);

    // ==================== LISTE COMPLÈTE ====================

    @Query("SELECT s FROM BankStatement s ORDER BY s.createdAt DESC")
    List<BankStatement> findAllOrderByCreatedAtDesc();

    Page<BankStatement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // ==================== MOIS PRÉCÉDENT ====================

    // ==================== RELEVÉS INVALIDES ====================

    @Query("""
                SELECT s FROM BankStatement s
                WHERE s.isBalanceValid = false
                   OR s.isContinuityValid = false
                   OR s.errorTransactionCount > 0
                ORDER BY s.createdAt DESC
            """)
    List<BankStatement> findInvalidStatements();

    // ==================== STATISTIQUES ====================

    @Query("SELECT COUNT(DISTINCT s.rib) FROM BankStatement s")
    long countDistinctRibs();

    @Query("""
                SELECT AVG(s.overallConfidence)
                FROM BankStatement s
                WHERE s.status = :status
                  AND s.overallConfidence IS NOT NULL
            """)
    Double averageConfidenceByStatus(@Param("status") BankStatus status);

    // ==================== RECHERCHE AVANCÉE ====================

    @Query("""
                SELECT s FROM BankStatement s
                WHERE (:rib IS NULL OR s.rib = :rib)
                  AND (:year IS NULL OR s.year = :year)
                  AND (:month IS NULL OR s.month = :month)
                  AND (:status IS NULL OR s.status = :status)
                ORDER BY s.year DESC, s.month DESC
            """)
    Page<BankStatement> search(
            @Param("rib") String rib,
            @Param("year") Integer year,
            @Param("month") Integer month,
            @Param("status") BankStatus status,
            Pageable pageable);
}
