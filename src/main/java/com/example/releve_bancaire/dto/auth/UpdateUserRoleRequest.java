package com.example.releve_bancaire.dto.auth;

import com.example.releve_bancaire.entity.auth.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserRoleRequest {
    @NotNull
    private UserRole role;
}
