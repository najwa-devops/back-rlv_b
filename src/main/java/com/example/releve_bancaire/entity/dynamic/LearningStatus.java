package com.example.releve_bancaire.entity.dynamic;

public enum LearningStatus {

    PENDING, // En attente de validation
    APPROVED, // Validé par un admin
    REJECTED, // Rejeté
    AUTO_APPROVED // Auto-approuvé (haute confiance)

}
