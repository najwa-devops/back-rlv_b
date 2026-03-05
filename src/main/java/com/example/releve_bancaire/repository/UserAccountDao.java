package com.example.releve_bancaire.repository;

import com.example.releve_bancaire.entity.auth.UserAccount;
import com.example.releve_bancaire.entity.auth.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAccountDao extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    List<UserAccount> findByRole(UserRole role);
}
