package com.example.releve_bancaire.dto.auth;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserActiveRequest {
    @NotNull
    private Boolean active;
}
