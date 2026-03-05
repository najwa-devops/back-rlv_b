package com.example.releve_bancaire.controller.settings;

import com.example.releve_bancaire.dto.settings.UpsertDossierGeneralParamsRequest;
import com.example.releve_bancaire.entity.auth.Dossier;
import com.example.releve_bancaire.entity.auth.DossierGeneralParams;
import com.example.releve_bancaire.entity.auth.UserRole;
import com.example.releve_bancaire.repository.DossierDao;
import com.example.releve_bancaire.repository.DossierGeneralParamsDao;
import com.example.releve_bancaire.security.RequireRole;
import com.example.releve_bancaire.servises.auth.AuthService;
import com.example.releve_bancaire.servises.auth.SessionKeys;
import com.example.releve_bancaire.servises.auth.SessionUser;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/general-params")
@RequiredArgsConstructor
@RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
public class GeneralParamsController {

    private final DossierGeneralParamsDao generalParamsDao;
    private final DossierDao dossierDao;
    private final AuthService authService;

    @GetMapping
    public ResponseEntity<?> getParams(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnNull(session, dossierId);
        if (dossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        DossierGeneralParams params = generalParamsDao.findByDossierId(dossierId).orElse(null);
        return ResponseEntity.ok(Map.of("params", toResponseMap(params, dossierId)));
    }

    @PutMapping
    public ResponseEntity<?> upsertParams(
            @RequestBody UpsertDossierGeneralParamsRequest request,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        dossierId = resolveDossierOrReturnNull(session, dossierId);
        if (dossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        SessionUser sessionUser = authService.requireSessionUser(session);
        Dossier dossier = requireDossierForUser(sessionUser, dossierId);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "dossier_forbidden"));
        }

        DossierGeneralParams params = generalParamsDao.findByDossierId(dossierId)
                .orElseGet(() -> {
                    DossierGeneralParams created = new DossierGeneralParams();
                    created.setDossier(dossier);
                    return created;
                });

        applyRequest(params, request);
        DossierGeneralParams saved = generalParamsDao.save(params);

        return ResponseEntity.ok(Map.of(
                "message", "general_params_saved",
                "params", toResponseMap(saved, dossierId)));
    }

    private void applyRequest(DossierGeneralParams target, UpsertDossierGeneralParamsRequest request) {
        target.setCompanyName(trimOrNull(request.getCompanyName()));
        target.setAddress(trimOrNull(request.getAddress()));
        target.setLegalForm(trimOrNull(request.getLegalForm()));
        target.setRcNumber(trimOrNull(request.getRcNumber()));
        target.setIfNumber(trimOrNull(request.getIfNumber()));
        target.setTsc(trimOrNull(request.getTsc()));
        target.setActivity(trimOrNull(request.getActivity()));
        target.setCategory(trimOrNull(request.getCategory()));
        target.setProfessionalTax(trimOrNull(request.getProfessionalTax()));
        target.setIce(trimOrNull(request.getIce()));
        target.setCniOrResidenceCard(trimOrNull(request.getCniOrResidenceCard()));
        target.setLegalRepresentative(trimOrNull(request.getLegalRepresentative()));
        target.setCmRate(request.getCmRate());
        target.setIsRate(request.getIsRate());
        target.setCapital(request.getCapital());
        target.setSubjectToRas(Boolean.TRUE.equals(request.getSubjectToRas()));
        target.setIndividualPerson(Boolean.TRUE.equals(request.getIndividualPerson()));
        target.setHasFiscalRegularityCertificate(Boolean.TRUE.equals(request.getHasFiscalRegularityCertificate()));
    }

    private String trimOrNull(String value) {
        if (value == null)
            return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Map<String, Object> toResponseMap(DossierGeneralParams params, Long dossierId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dossierId", dossierId);
        response.put("companyName", params != null ? params.getCompanyName() : null);
        response.put("address", params != null ? params.getAddress() : null);
        response.put("legalForm", params != null ? params.getLegalForm() : null);
        response.put("rcNumber", params != null ? params.getRcNumber() : null);
        response.put("ifNumber", params != null ? params.getIfNumber() : null);
        response.put("tsc", params != null ? params.getTsc() : null);
        response.put("activity", params != null ? params.getActivity() : null);
        response.put("category", params != null ? params.getCategory() : null);
        response.put("professionalTax", params != null ? params.getProfessionalTax() : null);
        response.put("ice", params != null ? params.getIce() : null);
        response.put("cniOrResidenceCard", params != null ? params.getCniOrResidenceCard() : null);
        response.put("legalRepresentative", params != null ? params.getLegalRepresentative() : null);
        response.put("cmRate", params != null && params.getCmRate() != null ? params.getCmRate() : BigDecimal.ZERO);
        response.put("isRate", params != null && params.getIsRate() != null ? params.getIsRate() : BigDecimal.ZERO);
        response.put("capital", params != null ? params.getCapital() : null);
        response.put("subjectToRas", params != null && Boolean.TRUE.equals(params.getSubjectToRas()));
        response.put("individualPerson", params != null && Boolean.TRUE.equals(params.getIndividualPerson()));
        response.put("hasFiscalRegularityCertificate",
                params != null && Boolean.TRUE.equals(params.getHasFiscalRegularityCertificate()));
        return response;
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

    private Long resolveDossierOrReturnNull(HttpSession session, Long requestedDossierId) {
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
}
