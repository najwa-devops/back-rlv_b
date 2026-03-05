package com.example.releve_bancaire.controller.auth;

import com.example.releve_bancaire.dto.auth.CreateDossierRequest;
import com.example.releve_bancaire.entity.auth.Dossier;
import com.example.releve_bancaire.entity.auth.UserAccount;
import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.repository.DossierDao;
import com.example.releve_bancaire.repository.UserAccountDao;
import com.example.releve_bancaire.servises.auth.AuthService;
import com.example.releve_bancaire.servises.auth.SessionUser;
import com.example.releve_bancaire.servises.auth.SessionKeys;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dossiers")
@RequiredArgsConstructor
public class DossierController {

    private final DossierDao dossierDao;
    private final UserAccountDao userAccountDao;
    private final AuthService authService;

    @PostMapping
    public ResponseEntity<?> createDossier(@Valid @RequestBody CreateDossierRequest request, HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long comptableId = sessionUser.isAdmin() ? request.getComptableId() : sessionUser.id();
        if (comptableId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "comptable_id_required"));
        }

        UserAccount comptable = userAccountDao.findById(comptableId)
                .filter(u -> u.getRole() == UserRole.COMPTABLE || u.getRole() == UserRole.ADMIN)
                .orElse(null);

        if (comptable == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "comptable_not_found"));
        }

        UserAccount client = resolveClient(request);
        if (client == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "client_invalid"));
        }

        Dossier dossier = new Dossier();
        dossier.setName(request.getDossierName());
        dossier.setClient(client);
        dossier.setComptable(comptable);
        dossier.setActive(true);

        Dossier saved = dossierDao.save(dossier);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", saved.getId(),
                "name", saved.getName(),
                "clientId", saved.getClient().getId(),
                "comptableId", saved.getComptable().getId()));
    }

    @GetMapping
    public ResponseEntity<?> listDossiers(HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        List<Dossier> dossiers;
        if (sessionUser.isAdmin()) {
            dossiers = dossierDao.findAll();
        } else if (sessionUser.isClient()) {
            dossiers = dossierDao.findByClientId(sessionUser.id());
        } else {
            dossiers = dossierDao.findByComptableId(sessionUser.id());
        }

        List<Map<String, Object>> response = dossiers.stream()
                .map(d -> Map.<String, Object>of(
                        "id", d.getId(),
                        "name", d.getName(),
                        "clientId", d.getClientId(),
                        "comptableId", d.getComptableId(),
                        "active", d.getActive()))
                .toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/active")
    public ResponseEntity<?> setActiveDossier(@RequestBody Map<String, Object> request, HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Object rawId = request.get("dossierId");
        if (rawId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_id_required"));
        }

        Long dossierId;
        try {
            dossierId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_id_invalid"));
        }

        Dossier dossier = resolveDossierForUser(sessionUser, dossierId);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "dossier_forbidden"));
        }

        session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, dossier.getId());

        return ResponseEntity.ok(Map.of(
                "id", dossier.getId(),
                "name", dossier.getName(),
                "clientId", dossier.getClientId(),
                "comptableId", dossier.getComptableId()));
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActiveDossier(HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Object rawId = session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
        if (rawId == null) {
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return ResponseEntity.ok(Map.of(
                            "id", fallback.getId(),
                            "name", fallback.getName(),
                            "clientId", fallback.getClientId(),
                            "comptableId", fallback.getComptableId()));
                }
            }
            return ResponseEntity.ok(Map.of("id", null));
        }

        Long dossierId;
        try {
            dossierId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException ex) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return ResponseEntity.ok(Map.of(
                            "id", fallback.getId(),
                            "name", fallback.getName(),
                            "clientId", fallback.getClientId(),
                            "comptableId", fallback.getComptableId()));
                }
            }
            return ResponseEntity.ok(Map.of("id", null));
        }

        Dossier dossier = resolveDossierForUser(sessionUser, dossierId);
        if (dossier == null) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return ResponseEntity.ok(Map.of(
                            "id", fallback.getId(),
                            "name", fallback.getName(),
                            "clientId", fallback.getClientId(),
                            "comptableId", fallback.getComptableId()));
                }
            }
            return ResponseEntity.ok(Map.of("id", null));
        }

        return ResponseEntity.ok(Map.of(
                "id", dossier.getId(),
                "name", dossier.getName(),
                "clientId", dossier.getClientId(),
                "comptableId", dossier.getComptableId()));
    }

    private UserAccount resolveClient(CreateDossierRequest request) {
        if (request.getClientId() != null) {
            return userAccountDao.findById(request.getClientId())
                    .filter(u -> u.getRole() == UserRole.CLIENT)
                    .orElse(null);
        }

        if (request.getClientUsername() == null || request.getClientUsername().isBlank()
                || request.getClientPassword() == null || request.getClientPassword().isBlank()) {
            return null;
        }

        if (userAccountDao.existsByUsername(request.getClientUsername())) {
            return null;
        }

        UserAccount client = new UserAccount();
        client.setUsername(request.getClientUsername());
        client.setPassword(request.getClientPassword());
        client.setRole(UserRole.CLIENT);
        client.setDisplayName(request.getClientDisplayName());
        client.setActive(true);

        return userAccountDao.save(client);
    }

    private Dossier resolveDossierForUser(SessionUser sessionUser, Long dossierId) {
        if (sessionUser.isAdmin()) {
            return dossierDao.findById(dossierId).orElse(null);
        }
        if (sessionUser.isClient()) {
            return dossierDao.findByIdAndClientId(dossierId, sessionUser.id()).orElse(null);
        }
        return dossierDao.findByIdAndComptableId(dossierId, sessionUser.id()).orElse(null);
    }
}
