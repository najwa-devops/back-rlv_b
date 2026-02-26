package com.example.releve_bancaire.auth.controller;

import com.example.releve_bancaire.auth.dto.CurrentUserResponse;
import com.example.releve_bancaire.auth.dto.LoginRequest;
import com.example.releve_bancaire.auth.dto.LoginResponse;
import com.example.releve_bancaire.auth.entity.AppUser;
import com.example.releve_bancaire.auth.repository.AppUserRepository;
import com.example.releve_bancaire.auth.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(request.email()).orElse(null);
        boolean invalid = user == null
                || !Boolean.TRUE.equals(user.getActive())
                || !passwordEncoder.matches(request.password(), user.getPasswordHash());
        if (invalid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Email ou mot de passe invalide"));
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        LoginResponse response = new LoginResponse(
                token,
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getName(),
                Boolean.TRUE.equals(user.getActive()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Non authentifié"));
        }

        AppUser user;
        try {
            String email = jwtService.extractEmail(authHeader.substring(7));
            user = appUserRepository.findByEmailIgnoreCase(email).orElse(null);
        } catch (Exception e) {
            user = null;
        }
        if (user == null || !Boolean.TRUE.equals(user.getActive())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Non authentifié"));
        }

        CurrentUserResponse response = new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                Boolean.TRUE.equals(user.getActive()));
        return ResponseEntity.ok(response);
    }
}
