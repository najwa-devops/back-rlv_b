package com.example.releve_bancaire.servises.account_tier;

import com.example.releve_bancaire.dto.account_tier.CreateTierRequest;
import com.example.releve_bancaire.dto.account_tier.TierDto;
import com.example.releve_bancaire.dto.account_tier.UpdateTierRequest;
import com.example.releve_bancaire.entity.account_tier.Tier;
import com.example.releve_bancaire.entity.auth.Dossier;
import com.example.releve_bancaire.repository.TierDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TierService {

    private final TierDao tierDao;

    @Transactional
    public TierDto createTier(CreateTierRequest request, Long dossierId) {
        log.info("Creation tier dossier={} libelle={}", dossierId, request.getLibelle());
        normalizeCreateRequest(request);
        request.validate();
        validateUniqueness(dossierId, request, null);

        Tier tier = buildTierFromRequest(request, dossierId);
        Tier saved = tierDao.save(tier);
        return TierDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Optional<TierDto> getTierById(Long id, Long dossierId) {
        return tierDao.findByIdAndDossierId(id, dossierId).map(TierDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public Optional<TierDto> getTierByTierNumber(String tierNumber, Long dossierId) {
        String normalizedTierNumber = normalizeCode(tierNumber);
        if (normalizedTierNumber == null) {
            return Optional.empty();
        }
        return tierDao.findByDossierIdAndTierNumber(dossierId, normalizedTierNumber).map(TierDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public Optional<TierDto> getTierByIfNumber(String ifNumber, Long dossierId) {
        String normalizedIfNumber = normalizeIdentifier(ifNumber);
        if (normalizedIfNumber == null) {
            return Optional.empty();
        }
        return tierDao.findByDossierIdAndIfNumber(dossierId, normalizedIfNumber).map(TierDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public Optional<TierDto> getTierByIce(String ice, Long dossierId) {
        String normalizedIce = normalizeIdentifier(ice);
        if (normalizedIce == null) {
            return Optional.empty();
        }
        return tierDao.findByDossierIdAndIce(dossierId, normalizedIce).map(TierDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public List<TierDto> getAllActiveTiers(Long dossierId) {
        return tierDao.findByDossierIdAndActiveTrueOrderByLibelleAsc(dossierId).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> getAllTiers(Long dossierId) {
        return tierDao.findByDossierIdOrderByLibelleAsc(dossierId).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> searchTiers(String query, Long dossierId) {
        if (query == null || query.isBlank()) {
            return getAllActiveTiers(dossierId);
        }
        return tierDao.search(dossierId, query).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> getTiersWithAccountingConfig(Long dossierId) {
        return tierDao.findWithAccountingConfiguration(dossierId).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> getTiersWithoutAccountingConfig(Long dossierId) {
        return tierDao.findWithoutAccountingConfiguration(dossierId).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> getTiersWithIce(Long dossierId) {
        return tierDao.findWithIce(dossierId).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> getTiersWithIfNumber(Long dossierId) {
        return tierDao.findWithIfNumber(dossierId).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> getTiersWithoutFiscalIdentifier(Long dossierId) {
        return tierDao.findWithoutFiscalIdentifier(dossierId).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> getTiersAuxiliaire(Long dossierId) {
        return tierDao.findByDossierIdAndAuxiliaireMode(dossierId, true).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TierDto> getTiersNormal(Long dossierId) {
        return tierDao.findByDossierIdAndAuxiliaireMode(dossierId, false).stream()
                .map(TierDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public TierDto updateTier(Long id, UpdateTierRequest request, Long dossierId) {
        Tier tier = tierDao.findByIdAndDossierId(id, dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Tier non trouve: " + id));

        normalizeUpdateRequest(request);
        request.validate();
        validateUniquenessForUpdate(dossierId, request, id);
        updateTierFields(tier, request);
        Tier saved = tierDao.save(tier);
        return TierDto.fromEntity(saved);
    }

    @Transactional
    public void deactivateTier(Long id, Long dossierId) {
        Tier tier = tierDao.findByIdAndDossierId(id, dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Tier non trouve: " + id));
        tier.deactivate();
        tierDao.save(tier);
    }

    @Transactional
    public void activateTier(Long id, Long dossierId) {
        Tier tier = tierDao.findByIdAndDossierId(id, dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Tier non trouve: " + id));
        tier.activate();
        tierDao.save(tier);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStatistics(Long dossierId) {
        long total = tierDao.countByDossierId(dossierId);
        long actifs = tierDao.countByDossierIdAndActiveTrue(dossierId);
        long withConfig = tierDao.countWithAccountingConfiguration(dossierId);
        long withoutConfig = tierDao.countWithoutAccountingConfiguration(dossierId);
        long withIce = tierDao.countWithIce(dossierId);
        long withIf = tierDao.countWithIfNumber(dossierId);
        long auxiliaire = tierDao.countByDossierIdAndAuxiliaireMode(dossierId, true);
        long normal = tierDao.countByDossierIdAndAuxiliaireMode(dossierId, false);

        return Map.of(
                "total", total,
                "actifs", actifs,
                "inactifs", total - actifs,
                "avecConfigComptable", withConfig,
                "sansConfigComptable", withoutConfig,
                "avecICE", withIce,
                "avecIF", withIf,
                "sansIdentifiant", tierDao.findWithoutFiscalIdentifier(dossierId).size(),
                "modeAuxiliaire", auxiliaire,
                "modeNormal", normal);
    }

    private void validateUniqueness(Long dossierId, CreateTierRequest request, Long excludeId) {
        if (request.getTierNumber() != null && !request.getTierNumber().isBlank()) {
            Optional<Tier> existing = tierDao.findByDossierIdAndTierNumber(dossierId, request.getTierNumber());
            if (existing.isPresent() && !existing.get().getId().equals(excludeId)) {
                throw new IllegalArgumentException("Un tier existe deja avec le numero: " + request.getTierNumber());
            }
        }

        if (request.getIfNumber() != null && !request.getIfNumber().isBlank()) {
            Optional<Tier> existing = tierDao.findByDossierIdAndIfNumber(dossierId, request.getIfNumber());
            if (existing.isPresent() && !existing.get().getId().equals(excludeId)) {
                throw new IllegalArgumentException("Un tier existe deja avec l'IF: " + request.getIfNumber());
            }
        }

        // if (request.getIce() != null && !request.getIce().isBlank()) {
        // Optional<Tier> existing = tierDao.findByDossierIdAndIce(dossierId,
        // request.getIce());
        // if (existing.isPresent() && !existing.get().getId().equals(excludeId)) {
        // throw new IllegalArgumentException("Un tier existe deja avec l'ICE: " +
        // request.getIce());
        // }
        // }
    }

    private void validateUniquenessForUpdate(Long dossierId, UpdateTierRequest request, Long tierId) {
        if (request.getTierNumber() != null && !request.getTierNumber().isBlank()) {
            Optional<Tier> existing = tierDao.findByDossierIdAndTierNumber(dossierId, request.getTierNumber());
            if (existing.isPresent() && !existing.get().getId().equals(tierId)) {
                throw new IllegalArgumentException(
                        "Un autre tier existe deja avec le numero: " + request.getTierNumber());
            }
        }

        if (request.getIfNumber() != null && !request.getIfNumber().isBlank()) {
            Optional<Tier> existing = tierDao.findByDossierIdAndIfNumber(dossierId, request.getIfNumber());
            if (existing.isPresent() && !existing.get().getId().equals(tierId)) {
                throw new IllegalArgumentException("Un autre tier existe deja avec l'IF: " + request.getIfNumber());
            }
        }

        // if (request.getIce() != null && !request.getIce().isBlank()) {
        // Optional<Tier> existing = tierDao.findByDossierIdAndIce(dossierId,
        // request.getIce());
        // if (existing.isPresent() && !existing.get().getId().equals(tierId)) {
        // throw new IllegalArgumentException("Un autre tier existe deja avec l'ICE: " +
        // request.getIce());
        // }
        // }
    }

    private Tier buildTierFromRequest(CreateTierRequest request, Long dossierId) {
        Dossier dossier = new Dossier();
        dossier.setId(dossierId);

        return Tier.builder()
                .dossier(dossier)
                .auxiliaireMode(request.getAuxiliaireMode())
                .tierNumber(request.getTierNumber())
                .collectifAccount(request.getCollectifAccount())
                .libelle(request.getLibelle())
                .ifNumber(request.getIfNumber())
                .ice(request.getIce())
                .rcNumber(request.getRcNumber())
                .defaultChargeAccount(request.getDefaultChargeAccount())
                .tvaAccount(request.getTvaAccount())
                .defaultTvaRate(request.getDefaultTvaRate())
                .active(request.getActive() != null ? request.getActive() : true)
                .createdBy(request.getCreatedBy())
                .build();
    }

    private void updateTierFields(Tier tier, UpdateTierRequest request) {
        if (request.getTierNumber() != null) {
            tier.setTierNumber(request.getTierNumber());
        }
        if (request.getCollectifAccount() != null) {
            tier.setCollectifAccount(request.getCollectifAccount());
        }
        if (request.getLibelle() != null) {
            tier.setLibelle(request.getLibelle());
        }
        if (request.getIfNumber() != null) {
            tier.setIfNumber(request.getIfNumber());
        }
        if (request.getIce() != null) {
            tier.setIce(request.getIce());
        }
        if (request.getRcNumber() != null) {
            tier.setRcNumber(request.getRcNumber());
        }
        if (request.getDefaultChargeAccount() != null) {
            tier.setDefaultChargeAccount(request.getDefaultChargeAccount());
        }
        if (request.getTvaAccount() != null) {
            tier.setTvaAccount(request.getTvaAccount());
        }
        if (request.getDefaultTvaRate() != null) {
            tier.setDefaultTvaRate(request.getDefaultTvaRate());
        }
        if (request.getActive() != null) {
            tier.setActive(request.getActive());
        }
        if (request.getUpdatedBy() != null) {
            tier.setUpdatedBy(request.getUpdatedBy());
        }
    }

    private void normalizeCreateRequest(CreateTierRequest request) {
        request.setTierNumber(normalizeCode(request.getTierNumber()));
        request.setCollectifAccount(normalizeCode(request.getCollectifAccount()));
        request.setLibelle(normalizeText(request.getLibelle()));
        request.setIfNumber(normalizeIdentifier(request.getIfNumber()));
        request.setIce(normalizeIdentifier(request.getIce()));
        request.setRcNumber(normalizeIdentifier(request.getRcNumber()));
        request.setDefaultChargeAccount(normalizeCode(request.getDefaultChargeAccount()));
        request.setTvaAccount(normalizeCode(request.getTvaAccount()));
        request.setCreatedBy(normalizeText(request.getCreatedBy()));
    }

    private void normalizeUpdateRequest(UpdateTierRequest request) {
        request.setTierNumber(normalizeCode(request.getTierNumber()));
        request.setCollectifAccount(normalizeCode(request.getCollectifAccount()));
        request.setLibelle(normalizeText(request.getLibelle()));
        request.setIfNumber(normalizeIdentifier(request.getIfNumber()));
        request.setIce(normalizeIdentifier(request.getIce()));
        request.setRcNumber(normalizeIdentifier(request.getRcNumber()));
        request.setDefaultChargeAccount(normalizeCode(request.getDefaultChargeAccount()));
        request.setTvaAccount(normalizeCode(request.getTvaAccount()));
        request.setUpdatedBy(normalizeText(request.getUpdatedBy()));
    }

    private String normalizeCode(String value) {
        if (value == null)
            return null;
        String normalized = value.trim().replaceAll("\\s+", "");
        if (normalized.isEmpty())
            return null;
        return normalized.toUpperCase();
    }

    private String normalizeIdentifier(String value) {
        if (value == null)
            return null;
        String normalized = value.trim().replaceAll("\\s+", "");
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeText(String value) {
        if (value == null)
            return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
