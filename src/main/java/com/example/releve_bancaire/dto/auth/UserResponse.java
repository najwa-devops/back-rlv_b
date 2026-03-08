package com.example.releve_bancaire.dto.auth;

import com.example.releve_bancaire.entity.auth.UserAccount;
import com.example.releve_bancaire.entity.auth.UserRole;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserResponse {
    private Long id;
    private String username;
    private UserRole role;
    private String displayName;
    private Boolean active;

    public static UserResponse fromEntity(UserAccount user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .displayName(user.getDisplayName())
                .active(user.getActive())
                .build();
    }
}
