package com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.controller;

import com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.dto.CmExpansionDTO;
import com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.dto.RapprochementResultDTO;
import com.example.releve_bancaire.liaison_rlv_b_ctr_mntq.service.CentreMonetiqueLiaisonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping({"/api/v2/centre-monetique", "/api/centre-monetique"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class CentreMonetiqueLiaisonController {

    private final CentreMonetiqueLiaisonService liaisonService;

    @GetMapping("/{id}/rapprochement")
    public ResponseEntity<?> rapprochement(@PathVariable("id") Long id) {
        Optional<RapprochementResultDTO> result = liaisonService.rapprochement(id);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
        }
        return ResponseEntity.ok(result.get());
    }

    /**
     * Retourne les lignes CM qui remplacent les transactions bancaires dans le releve donne.
     * Utilise par le frontend pour afficher les details CM directement dans la vue releve bancaire.
     * GET /api/v2/centre-monetique/statement/{statementId}/expansions
     */
    @GetMapping("/statement/{statementId}/expansions")
    public ResponseEntity<?> cmExpansionsForStatement(@PathVariable("statementId") Long statementId) {
        try {
            List<CmExpansionDTO> expansions = liaisonService.getCmExpansionsForStatement(statementId);
            return ResponseEntity.ok(expansions);
        } catch (Exception e) {
            log.error("Erreur cm-expansions pour statement {}: {}", statementId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }
}
