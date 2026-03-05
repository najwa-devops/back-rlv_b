package com.example.releve_bancaire.auth.dto;

public record LoginResponse(
        Long userId,
        String email,
        String role,
        String name,
        boolean active) {
}

