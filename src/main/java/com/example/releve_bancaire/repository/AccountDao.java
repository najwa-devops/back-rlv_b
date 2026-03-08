package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.account_tier.Compte;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface AccountDao extends JpaRepository<Compte, String> {

    // ===================== RECHERCHE PAR NUMERO =====================
    Optional<Compte> findByNumero(String numero);

    boolean existsByNumero(String numero);

    // ===================== RECHERCHE PAR CLASSE =====================
    List<Compte> findByClasse(Integer classe);

    @Query("SELECT c FROM Compte c WHERE c.classe = :classe AND c.active = true ORDER BY c.numero ASC")
    List<Compte> findActiveByClasse(@Param("classe") Integer classe);

    List<Compte> findByClasseAndActiveTrue(Integer classe);

    // ===================== COMPTES ACTIFS =====================
    List<Compte> findByActiveTrueOrderByNumeroAsc();

    List<Compte> findByNumeroInAndActiveTrue(Set<String> numeros);

    List<Compte> findAllByOrderByNumeroAsc();

    // ===================== RECHERCHE PAR LIBELLÉ =====================
    @Query("SELECT c FROM Compte c WHERE LOWER(c.libelle) LIKE LOWER(CONCAT('%', :libelle, '%')) AND c.active = true ORDER BY c.numero ASC")
    List<Compte> searchByLibelle(@Param("libelle") String libelle);

    @Query("SELECT c FROM Compte c WHERE " +
            "(LOWER(c.numero) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(c.libelle) LIKE LOWER(CONCAT('%', :query, '%'))) " +
            "AND c.active = true " +
            "ORDER BY c.numero ASC")
    List<Compte> search(@Param("query") String query);

    @Query("SELECT c FROM Compte c WHERE " +
            "(LOWER(c.numero) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(c.libelle) LIKE LOWER(CONCAT('%', :query, '%'))) " +
            "AND c.active = true " +
            "ORDER BY c.numero ASC")
    List<Compte> searchActive(@Param("query") String query);

    // ===================== COMPTES SPÉCIFIQUES PAR TYPE =====================
    @Query("SELECT c FROM Compte c WHERE c.numero LIKE '441%' AND c.active = true ORDER BY c.numero ASC")
    List<Compte> findFournisseurAccounts();

    @Query("SELECT c FROM Compte c WHERE c.classe = 6 AND c.active = true ORDER BY c.numero ASC")
    List<Compte> findChargeAccounts();

    @Query("SELECT c FROM Compte c WHERE c.numero LIKE '3455%' AND c.active = true ORDER BY c.numero ASC")
    List<Compte> findTvaAccounts();

    // ===================== STATISTIQUES =====================
    long countByActiveTrue();

    long countByClasse(Integer classe);

    @Query("SELECT COUNT(c) FROM Compte c WHERE c.classe = :classe AND c.active = true")
    long countActiveByClasse(@Param("classe") Integer classe);
}

