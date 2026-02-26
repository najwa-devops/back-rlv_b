package com.example.releve_bancaire.auth.dto;

public record CurrentUserResponse(
        Long id,
        String email,
        String name,
        String role,
        boolean active) {
}

