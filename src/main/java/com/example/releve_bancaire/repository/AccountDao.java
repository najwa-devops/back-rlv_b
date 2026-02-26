package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.account_tier.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface AccountDao extends JpaRepository<Account, Long> {

    // ===================== RECHERCHE PAR CODE =====================
    Optional<Account> findByCode(String code);

    boolean existsByCode(String code);

    // ===================== RECHERCHE PAR CLASSE =====================
    List<Account> findByClasse(Integer classe);

    @Query("SELECT a FROM Account a WHERE a.classe = :classe AND a.active = true ORDER BY a.code ASC")
    List<Account> findActiveByClasse(@Param("classe") Integer classe);

    List<Account> findByClasseAndActiveTrue(Integer classe);

    // ===================== COMPTES ACTIFS =====================
    List<Account> findByActiveTrueOrderByCodeAsc();

    List<Account> findByCodeInAndActiveTrue(Set<String> codes);

    List<Account> findAllByOrderByCodeAsc();

    // ===================== RECHERCHE PAR LIBELLÉ =====================
    @Query("SELECT a FROM Account a WHERE LOWER(a.libelle) LIKE LOWER(CONCAT('%', :libelle, '%')) AND a.active = true ORDER BY a.code ASC")
    List<Account> searchByLibelle(@Param("libelle") String libelle);

    @Query("SELECT a FROM Account a WHERE " +
            "(LOWER(a.code) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(a.libelle) LIKE LOWER(CONCAT('%', :query, '%'))) " +
            "AND a.active = true " +
            "ORDER BY a.code ASC")
    List<Account> search(@Param("query") String query);

    @Query("SELECT a FROM Account a WHERE " +
            "(LOWER(a.code) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(a.libelle) LIKE LOWER(CONCAT('%', :query, '%'))) " +
            "AND a.active = true " +
            "ORDER BY a.code ASC")
    List<Account> searchActive(@Param("query") String query);

    // ===================== COMPTES SPÉCIFIQUES PAR TYPE =====================
    @Query("SELECT a FROM Account a WHERE a.code LIKE '441%' AND a.active = true ORDER BY a.code ASC")
    List<Account> findFournisseurAccounts();

    @Query("SELECT a FROM Account a WHERE a.classe = 6 AND a.active = true ORDER BY a.code ASC")
    List<Account> findChargeAccounts();

    @Query("SELECT a FROM Account a WHERE a.code LIKE '3455%' AND a.active = true ORDER BY a.code ASC")
    List<Account> findTvaAccounts();

    // ===================== STATISTIQUES =====================
    long countByActiveTrue();


    long countByClasse(Integer classe);

    @Query("SELECT COUNT(a) FROM Account a WHERE a.classe = :classe AND a.active = true")
    long countActiveByClasse(@Param("classe") Integer classe);
}

