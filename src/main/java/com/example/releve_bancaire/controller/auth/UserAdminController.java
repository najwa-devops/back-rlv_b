package com.example.releve_bancaire.controller.auth;

import com.example.releve_bancaire.dto.auth.CreateUserRequest;
import com.example.releve_bancaire.dto.auth.UpdateUserActiveRequest;
import com.example.releve_bancaire.dto.auth.UpdateUserRoleRequest;
import com.example.releve_bancaire.dto.auth.UserResponse;
import com.example.releve_bancaire.entity.auth.UserAccount;
import com.example.releve_bancaire.repository.UserAccountDao;
import com.example.releve_bancaire.security.RequireRole;
import com.example.releve_bancaire.entity.auth.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@RequireRole({ UserRole.ADMIN })
public class UserAdminController {

    private final UserAccountDao userAccountDao;

    @PostMapping
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest request) {
        if (userAccountDao.existsByUsername(request.getUsername())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "username_exists"));
        }

        UserAccount user = new UserAccount();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        user.setRole(request.getRole());
        user.setDisplayName(request.getDisplayName());
        user.setActive(true);

        UserAccount saved = userAccountDao.save(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UserResponse.fromEntity(saved));
    }

    @GetMapping
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<List<UserResponse>> listUsers() {
        List<UserResponse> users = userAccountDao.findAll().stream()
                .map(UserResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(users);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivateUser(@PathVariable Long id) {
        return userAccountDao.findById(id)
                .map(user -> {
                    user.setActive(false);
                    userAccountDao.save(user);
                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<?> updateUserRole(@PathVariable Long id, @Valid @RequestBody UpdateUserRoleRequest request) {
        return userAccountDao.findById(id)
                .map(user -> {
                    user.setRole(request.getRole());
                    UserAccount saved = userAccountDao.save(user);
                    return ResponseEntity.ok(UserResponse.fromEntity(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<?> updateUserActive(@PathVariable Long id,
            @Valid @RequestBody UpdateUserActiveRequest request) {
        return userAccountDao.findById(id)
                .map(user -> {
                    user.setActive(request.getActive());
                    UserAccount saved = userAccountDao.save(user);
                    return ResponseEntity.ok(UserResponse.fromEntity(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
