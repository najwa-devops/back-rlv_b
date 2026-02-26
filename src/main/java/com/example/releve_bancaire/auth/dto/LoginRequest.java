package com.example.releve_bancaire.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Email requis")
        @Email(message = "Email invalide")
        String email,

        @NotBlank(message = "Mot de passe requis")
        String password) {
}

