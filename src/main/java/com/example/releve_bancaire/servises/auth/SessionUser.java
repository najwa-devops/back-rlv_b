package com.example.releve_bancaire.servises.auth;

import com.example.releve_bancaire.entity.auth.UserRole;

public record SessionUser(Long id, String username, UserRole role, String displayName) {
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean isComptable() {
        return role == UserRole.COMPTABLE;
    }

    public boolean isClient() {
        return role == UserRole.CLIENT;
    }
}
