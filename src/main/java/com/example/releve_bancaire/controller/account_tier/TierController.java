package com.example.releve_bancaire.controller.account_tier;

import com.example.releve_bancaire.dto.account_tier.CreateTierRequest;
import com.example.releve_bancaire.dto.account_tier.TierDto;
import com.example.releve_bancaire.dto.account_tier.UpdateTierRequest;
import com.example.releve_bancaire.entity.auth.Dossier;
import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.repository.DossierDao;
import com.example.releve_bancaire.security.RequireRole;
import com.example.releve_bancaire.servises.account_tier.TierService;
import com.example.releve_bancaire.servises.auth.AuthService;
import com.example.releve_bancaire.servises.auth.SessionKeys;
import com.example.releve_bancaire.servises.auth.SessionUser;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/accounting/tiers")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
@RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
public class TierController {

    private final TierService tierService;
    private final AuthService authService;
    private final DossierDao dossierDao;

    @PostMapping
    public ResponseEntity<?> createTier(
            @Valid @RequestBody CreateTierRequest request,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        try {
            TierDto tier = tierService.createTier(request, dossierId);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "Tier cree avec succes", "tier", tier));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", toTierConflictMessage(e)));
        } catch (Exception e) {
            log.error("Erreur creation tier: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors de la creation du tier"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTierById(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        Optional<TierDto> tierOpt = tierService.getTierById(id, dossierId);
        return tierOpt.<ResponseEntity<?>>map(tierDto -> ResponseEntity.ok(Map.of("tier", tierDto)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tier non trouve: " + id)));
    }

    @GetMapping("/by-tier-number/{tierNumber}")
    public ResponseEntity<?> getTierByTierNumber(
            @PathVariable String tierNumber,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        Optional<TierDto> tierOpt = tierService.getTierByTierNumber(tierNumber, dossierId);
        return tierOpt.<ResponseEntity<?>>map(tierDto -> ResponseEntity.ok(Map.of("tier", tierDto)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tier non trouve pour le numero: " + tierNumber)));
    }

    @GetMapping("/by-ice/{ice}")
    public ResponseEntity<?> getTierByIce(
            @PathVariable String ice,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        Optional<TierDto> tierOpt = tierService.getTierByIce(ice, dossierId);
        return tierOpt.<ResponseEntity<?>>map(tierDto -> ResponseEntity.ok(Map.of("tier", tierDto)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tier non trouve pour l'ICE: " + ice)));
    }

    @GetMapping("/by-if/{ifNumber}")
    public ResponseEntity<?> getTierByIfNumber(
            @PathVariable String ifNumber,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        Optional<TierDto> tierOpt = tierService.getTierByIfNumber(ifNumber, dossierId);
        return tierOpt.<ResponseEntity<?>>map(tierDto -> ResponseEntity.ok(Map.of("tier", tierDto)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Tier non trouve pour l'IF: " + ifNumber)));
    }

    @GetMapping
    public ResponseEntity<?> getAllTiers(
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = activeOnly ? tierService.getAllActiveTiers(dossierId)
                : tierService.getAllTiers(dossierId);
        return ResponseEntity.ok(Map.of("count", tiers.size(), "tiers", tiers));
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchTiers(
            @RequestParam String query,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = tierService.searchTiers(query, dossierId);
        return ResponseEntity.ok(Map.of("query", query, "count", tiers.size(), "tiers", tiers));
    }

    @GetMapping("/with-config")
    public ResponseEntity<?> getTiersWithConfig(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = tierService.getTiersWithAccountingConfig(dossierId);
        return ResponseEntity
                .ok(Map.of("filter", "avec configuration comptable", "count", tiers.size(), "tiers", tiers));
    }

    @GetMapping("/without-config")
    public ResponseEntity<?> getTiersWithoutConfig(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = tierService.getTiersWithoutAccountingConfig(dossierId);
        return ResponseEntity
                .ok(Map.of("filter", "sans configuration comptable", "count", tiers.size(), "tiers", tiers));
    }

    @GetMapping("/with-ice")
    public ResponseEntity<?> getTiersWithIce(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = tierService.getTiersWithIce(dossierId);
        return ResponseEntity.ok(Map.of("filter", "avec ICE", "count", tiers.size(), "tiers", tiers));
    }

    @GetMapping("/with-if")
    public ResponseEntity<?> getTiersWithIfNumber(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = tierService.getTiersWithIfNumber(dossierId);
        return ResponseEntity.ok(Map.of("filter", "avec IF", "count", tiers.size(), "tiers", tiers));
    }

    @GetMapping("/without-identifier")
    public ResponseEntity<?> getTiersWithoutFiscalIdentifier(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = tierService.getTiersWithoutFiscalIdentifier(dossierId);
        return ResponseEntity.ok(Map.of("filter", "sans identifiant fiscal", "count", tiers.size(), "tiers", tiers));
    }

    @GetMapping("/auxiliaire")
    public ResponseEntity<?> getTiersAuxiliaire(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = tierService.getTiersAuxiliaire(dossierId);
        return ResponseEntity.ok(Map.of("filter", "mode auxiliaire", "count", tiers.size(), "tiers", tiers));
    }

    @GetMapping("/normal")
    public ResponseEntity<?> getTiersNormal(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        List<TierDto> tiers = tierService.getTiersNormal(dossierId);
        return ResponseEntity.ok(Map.of("filter", "mode normal", "count", tiers.size(), "tiers", tiers));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateTier(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTierRequest request,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        try {
            TierDto tier = tierService.updateTier(id, request, dossierId);
            return ResponseEntity.ok(Map.of("message", "Tier mis a jour avec succes", "tier", tier));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", toTierConflictMessage(e)));
        } catch (Exception e) {
            log.error("Erreur mise a jour tier: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors de la mise a jour"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivateTier(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        try {
            tierService.deactivateTier(id, dossierId);
            return ResponseEntity.ok(Map.of("message", "Tier desactive avec succes", "tierId", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur desactivation tier: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors de la desactivation"));
        }
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<?> activateTier(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        try {
            tierService.activateTier(id, dossierId);
            return ResponseEntity.ok(Map.of("message", "Tier reactive avec succes", "tierId", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur activation tier: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors de la reactivation"));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStatistics(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnBadRequest(session, dossierId);
        if (dossierId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        return ResponseEntity.ok(tierService.getStatistics(dossierId));
    }

    private Dossier requireDossierForUser(SessionUser sessionUser, Long dossierId) {
        if (sessionUser == null || dossierId == null)
            return null;
        if (sessionUser.isAdmin())
            return dossierDao.findById(dossierId).orElse(null);
        if (sessionUser.isComptable())
            return dossierDao.findByIdAndComptableId(dossierId, sessionUser.id()).orElse(null);
        if (sessionUser.isClient())
            return dossierDao.findByIdAndClientId(dossierId, sessionUser.id()).orElse(null);
        return null;
    }

    private Long resolveDossierOrReturnBadRequest(HttpSession session, Long requestedDossierId) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null || session == null)
            return null;
        if (requestedDossierId != null) {
            Dossier requested = requireDossierForUser(sessionUser, requestedDossierId);
            if (requested == null) {
                return null;
            }
            session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, requestedDossierId);
            return requestedDossierId;
        }
        Object rawId = session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
        if (rawId == null)
            return null;
        Long dossierId;
        try {
            dossierId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException ex) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            return null;
        }
        if (requireDossierForUser(sessionUser, dossierId) == null) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            return null;
        }
        return dossierId;
    }

    private String toTierConflictMessage(DataIntegrityViolationException e) {
        String raw = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        String message = raw != null ? raw.toLowerCase() : "";
        if (message.contains("uk_tier_dossier_tier_number")) {
            return "Un tier existe deja avec ce numero dans ce dossier.";
        }
        if (message.contains("uk_tier_dossier_if_number")) {
            return "Un tier existe deja avec cet IF dans ce dossier.";
        }
        if (message.contains("uk_tier_dossier_ice")) {
            return "Un tier existe deja avec cet ICE dans ce dossier.";
        }
        if (message.contains("duplicate entry") && message.contains("tier_number")) {
            return "Un tier existe deja avec ce numero dans ce dossier.";
        }
        if (message.contains("duplicate entry") && message.contains("if_number")) {
            return "Un tier existe deja avec cet IF dans ce dossier.";
        }
        if (message.contains("duplicate entry") && message.contains("ice")) {
            return "Un tier existe deja avec cet ICE dans ce dossier.";
        }
        return "Conflit d'integrite lors de l'enregistrement du tier.";
    }
}
