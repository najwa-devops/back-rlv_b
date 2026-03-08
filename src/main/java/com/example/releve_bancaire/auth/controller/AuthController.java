package com.example.releve_bancaire.auth.controller;

import com.example.releve_bancaire.auth.dto.CurrentUserResponse;
import com.example.releve_bancaire.auth.dto.LoginRequest;
import com.example.releve_bancaire.auth.dto.LoginResponse;
import com.example.releve_bancaire.auth.entity.AppRole;
import com.example.releve_bancaire.auth.entity.AppUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Profile("no-auth")
public class AuthController {

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = buildLocalNoAuthUser(request.email());
        LoginResponse response = new LoginResponse(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getName(),
                Boolean.TRUE.equals(user.getActive()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        AppUser user = buildLocalNoAuthUser("local@no-auth");

        CurrentUserResponse response = new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                Boolean.TRUE.equals(user.getActive()));
        return ResponseEntity.ok(response);
    }

    private AppUser buildLocalNoAuthUser(String email) {
        AppUser user = new AppUser();
        user.setId(0L);
        user.setEmail(email != null && !email.isBlank() ? email : "local@no-auth");
        user.setName("Local User");
        user.setRole(AppRole.SUPER_ADMIN);
        user.setActive(true);
        user.setPasswordHash("no-auth");
        return user;
    }
}
