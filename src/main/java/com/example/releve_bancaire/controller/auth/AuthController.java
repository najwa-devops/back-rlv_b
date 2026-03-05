package com.example.releve_bancaire.controller.auth;

import com.example.releve_bancaire.dto.auth.LoginRequest;
import com.example.releve_bancaire.dto.auth.UserResponse;
import com.example.releve_bancaire.servises.auth.AuthService;
import com.example.releve_bancaire.servises.auth.SessionUser;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        return authService.authenticate(request.getUsername(), request.getPassword())
                .map(user -> {
                    authService.setSessionUser(session, user);
                    return ResponseEntity.ok(Map.of(
                            "message", "login_success",
                            "user", UserResponse.fromEntity(user)));
                })
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "invalid_credentials")));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        authService.clearSession(session);
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "logout_success"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", sessionUser.id());
        body.put("username", sessionUser.username());
        body.put("role", sessionUser.role());
        body.put("displayName", sessionUser.displayName());
        return ResponseEntity.ok(body);
    }
}
