package com.example.releve_bancaire.banking_repository;

import com.example.releve_bancaire.banking_entity.BankTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    @Query("SELECT t FROM BankTransaction t JOIN FETCH t.statement WHERE t.id = :id")
    Optional<BankTransaction> findByIdWithStatement(@Param("id") Long id);

    // ==================== RECHERCHE PAR RELEVÉ ====================

    List<BankTransaction> findByStatementIdOrderByTransactionIndexAsc(Long statementId);

    Page<BankTransaction> findByStatementId(Long statementId, Pageable pageable);

    long countByStatementId(Long statementId);

    @Modifying
    @Transactional
    @Query("DELETE FROM BankTransaction t WHERE t.statement.id = :statementId")
    void deleteByStatementId(@Param("statementId") Long statementId);

    @Modifying
    @Transactional
    @Query("""
            UPDATE BankTransaction t
            SET t.transactionIndex = t.transactionIndex + 1
            WHERE t.statement.id = :statementId
              AND t.transactionIndex >= :fromIndex
            """)
    int shiftIndexesFrom(@Param("statementId") Long statementId, @Param("fromIndex") Integer fromIndex);

    @Query("SELECT COALESCE(MAX(t.transactionIndex), -1) FROM BankTransaction t WHERE t.statement.id = :statementId")
    Integer findMaxTransactionIndex(@Param("statementId") Long statementId);

    // ==================== RECHERCHE PAR CATÉGORIE ====================

    List<BankTransaction> findByCategorie(String categorie);

    List<BankTransaction> findByStatementIdAndCategorie(Long statementId, String categorie);

    // ==================== RECHERCHE PAR SENS ====================

    List<BankTransaction> findBySens(String sens);

    List<BankTransaction> findByStatementIdAndSens(Long statementId, String sens);

    // ==================== RECHERCHE PAR VALIDATION ====================

    List<BankTransaction> findByNeedsReviewTrue();

    List<BankTransaction> findByIsValidFalse();

    Page<BankTransaction> findByNeedsReviewTrue(Pageable pageable);

    // ==================== RECHERCHE PAR LIBELLÉ ====================

    @Query("""
                SELECT t FROM BankTransaction t
                WHERE LOWER(t.libelle) LIKE LOWER(CONCAT('%', :keyword, '%'))
                ORDER BY t.dateOperation DESC
            """)
    List<BankTransaction> searchByLibelle(@Param("keyword") String keyword);

    @Query("""
                SELECT t FROM BankTransaction t
                WHERE t.statement.id = :statementId
                  AND LOWER(t.libelle) LIKE LOWER(CONCAT('%', :keyword, '%'))
                ORDER BY t.transactionIndex ASC
            """)
    List<BankTransaction> searchByLibelleForStatement(
            @Param("statementId") Long statementId,
            @Param("keyword") String keyword);

    // ==================== STATISTIQUES ====================

    @Query("""
                SELECT t.categorie,
                       COUNT(t),
                       COALESCE(SUM(t.debit), 0),
                       COALESCE(SUM(t.credit), 0)
                FROM BankTransaction t
                WHERE t.statement.id = :statementId
                GROUP BY t.categorie
            """)
    List<Object[]> sumAmountsByCategorieForStatement(@Param("statementId") Long statementId);

    @Query("""
                SELECT t.categorie,
                       COUNT(t),
                       COALESCE(SUM(t.debit), 0),
                       COALESCE(SUM(t.credit), 0)
                FROM BankTransaction t
                GROUP BY t.categorie
            """)
    List<Object[]> getGlobalStatisticsByCategorie();

    @Query("""
            SELECT t
            FROM BankTransaction t
            JOIN t.statement s
            WHERE MONTH(t.dateOperation) = :nmois
              AND (:year IS NULL OR YEAR(t.dateOperation) = :year)
              AND s.status IN (com.example.releve_bancaire.banking_entity.BankStatus.VALIDATED, com.example.releve_bancaire.banking_entity.BankStatus.COMPTABILISE)
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BankTransaction> findAccountingCandidates(@Param("nmois") int nmois, @Param("year") Integer year);

    // ==================== RAPPROCHEMENT CENTRE MONÉTIQUE ====================

    /**
     * Trouve les transactions bancaires dont le relevé a le RIB donné et dont
     * la date d'opération est dans la liste fournie.
     */
    @Query("""
            SELECT t
            FROM BankTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND t.dateOperation IN :dates
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BankTransaction> findByStatementRibAndDateOperationIn(
            @Param("rib") String rib,
            @Param("dates") java.util.Collection<java.time.LocalDate> dates);

    /**
     * Trouve les transactions bancaires CMI de type "VENTE PAR CARTE" pour un RIB donné.
     * Le libellé contient le numéro TPE : "VENTE PAR CARTE  000285".
     */
    @Query("""
            SELECT t
            FROM BankTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND UPPER(t.libelle) LIKE '%VENTE PAR CARTE%'
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BankTransaction> findByRibAndLibelleVenteParCarte(@Param("rib") String rib);

    /**
     * Trouve toutes les transactions crédit pour un RIB donné (CMI TPE rapprochement élargi).
     * Couvre "VENTE PAR CARTE 000293", "AZAR RESTAURAN294055", "ATTEATUDE CAF000294", etc.
     */
    @Query("""
            SELECT t
            FROM BankTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND t.credit IS NOT NULL
              AND t.credit > 0
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BankTransaction> findCreditTransactionsByRib(@Param("rib") String rib);

    /**
     * Trouve les transactions bancaires BARID BANK de type virement commerçant pour un RIB donné.
     * Le libellé contient "BARID CASH" : "RECEPTION D'UN VIREMENT DE Merchant ACQ86097 ... BARID CASH".
     */
    @Query("""
            SELECT t
            FROM BankTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND UPPER(t.libelle) LIKE '%BARID CASH%'
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BankTransaction> findByRibAndLibelleBaridCash(@Param("rib") String rib);
}
