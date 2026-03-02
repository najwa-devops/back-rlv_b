package com.example.releve_bancaire.accounting_controller;

import com.example.releve_bancaire.accounting_services.ComptabilisationWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({"/api/comptabilisation", "/api/v2/comptabilisation"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ComptabilisationWorkflowController {

    private final ComptabilisationWorkflowService workflowService;

    @PostMapping("/simulate")
    public ResponseEntity<?> simulate(@RequestBody SimulateRequest request) {
        try {
            if (request == null || request.statementId() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "statementId est obligatoire."));
            }
            return ResponseEntity.ok(workflowService.simulate(request.statementId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur lors de la simulation de comptabilisation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur interne lors de la simulation."));
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(
            @RequestBody ConfirmRequest request,
            @RequestHeader(name = "X-User-Id", required = false) String headerUserId) {
        try {
            if (request == null || request.simulationId() == null || request.simulationId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "simulationId est obligatoire."));
            }
            if (!Boolean.TRUE.equals(request.confirmed())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Confirmation explicite requise (confirmed=true)."));
            }
            String userId = request.userId();
            if (userId == null || userId.isBlank()) {
                userId = headerUserId;
            }
            return ResponseEntity.ok(workflowService.confirm(request.simulationId(), userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur lors de la confirmation de comptabilisation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur interne lors de la confirmation."));
        }
    }

    public record SimulateRequest(Long statementId) {
    }

    public record ConfirmRequest(String simulationId, String userId, Boolean confirmed) {
    }
}
