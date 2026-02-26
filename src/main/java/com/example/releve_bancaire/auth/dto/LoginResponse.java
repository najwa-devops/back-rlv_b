package com.example.releve_bancaire.auth.dto;

public record LoginResponse(
        String token,
        Long userId,
        String email,
        String role,
        String name,
        boolean active) {
}

