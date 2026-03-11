package com.example.releve_bancaire.centremonetique.controller;

import com.example.releve_bancaire.centremonetique.dto.CentreMonetiqueBatchDetailDTO;
import com.example.releve_bancaire.centremonetique.dto.CentreMonetiqueBatchSummaryDTO;
import com.example.releve_bancaire.centremonetique.dto.CentreMonetiqueExtractionRow;
import com.example.releve_bancaire.centremonetique.dto.CentreMonetiqueUploadResponseDTO;
import com.example.releve_bancaire.centremonetique.dto.RapprochementResultDTO;
import com.example.releve_bancaire.centremonetique.service.CentreMonetiqueStructureType;
import com.example.releve_bancaire.centremonetique.service.CentreMonetiqueWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping({"/api/v2/centre-monetique", "/api/centre-monetique"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class CentreMonetiqueController {

    private final CentreMonetiqueWorkflowService workflowService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadAndExtract(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "structure", required = false) String structure,
            @RequestParam(name = "rib", required = false) String rib) {

        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Fichier vide"));
            }

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
            if (!isSupported(filename, file.getContentType())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Type de fichier non supporte",
                        "filename", filename,
                        "contentType", file.getContentType() != null ? file.getContentType() : "unknown",
                        "supported", List.of("pdf", "png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff")));
            }

            CentreMonetiqueBatchDetailDTO detail = workflowService.uploadAndExtract(
                    file, year, CentreMonetiqueStructureType.fromNullable(structure), rib);
            return ResponseEntity.status(HttpStatus.CREATED).body(new CentreMonetiqueUploadResponseDTO(
                    "Extraction terminee",
                    detail,
                    detail.getRows()));
        } catch (Exception e) {
            log.error("Erreur extraction centre monetique: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

    @PutMapping("/{id}/rib")
    public ResponseEntity<?> updateRib(@PathVariable("id") Long id,
                                       @RequestBody Map<String, String> body) {
        String rib = body != null ? body.get("rib") : null;
        Optional<CentreMonetiqueBatchDetailDTO> detail = workflowService.updateRib(id, rib);
        if (detail.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
        }
        return ResponseEntity.ok(detail.get());
    }

    @GetMapping("/{id}/rapprochement")
    public ResponseEntity<?> rapprochement(@PathVariable("id") Long id) {
        Optional<RapprochementResultDTO> result = workflowService.rapprochement(id);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
        }
        return ResponseEntity.ok(result.get());
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<CentreMonetiqueBatchSummaryDTO> batches = workflowService.list(limit);
        return ResponseEntity.ok(Map.of(
                "count", batches.size(),
                "batches", batches));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable("id") Long id,
                                    @RequestParam(name = "includeRawOcr", defaultValue = "false") boolean includeRawOcr) {
        Optional<CentreMonetiqueBatchDetailDTO> detail = workflowService.findDetail(id, includeRawOcr);
        return detail.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable")));
    }

    @PostMapping("/{id}/reprocess")
    public ResponseEntity<?> reprocess(@PathVariable("id") Long id,
                                       @RequestParam(name = "year", required = false) Integer year,
                                       @RequestParam(name = "structure", required = false) String structure) {
        try {
            Optional<CentreMonetiqueBatchDetailDTO> detail = workflowService.reprocess(
                    id, year, CentreMonetiqueStructureType.fromNullable(structure));
            if (detail.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }
            return ResponseEntity.ok(new CentreMonetiqueUploadResponseDTO(
                    "Retraitement termine",
                    detail.get(),
                    detail.get().getRows()));
        } catch (Exception e) {
            log.error("Erreur reprocess centre monetique {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

    @PutMapping("/{id}/rows")
    public ResponseEntity<?> saveRows(@PathVariable("id") Long id,
                                      @RequestBody(required = false) List<CentreMonetiqueExtractionRow> rows) {
        Optional<CentreMonetiqueBatchDetailDTO> detail = workflowService.saveRows(id, rows);
        if (detail.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
        }
        return ResponseEntity.ok(new CentreMonetiqueUploadResponseDTO(
                "Lignes enregistrees",
                detail.get(),
                detail.get().getRows()));
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable("id") Long id) {
        Optional<Map<String, Object>> payload = workflowService.filePayload(id);
        if (payload.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        byte[] data = (byte[]) payload.get().get("data");
        if (data == null || data.length == 0) {
            return ResponseEntity.notFound().build();
        }

        String filename = String.valueOf(payload.get().get("filename"));
        String contentType = String.valueOf(payload.get().get("contentType"));
        ByteArrayResource resource = new ByteArrayResource(data);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long id) {
        boolean deleted = workflowService.delete(id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
        }
        return ResponseEntity.ok(Map.of("message", "Supprime", "id", id));
    }

    private boolean isSupported(String filename, String contentType) {
        String lower = filename.toLowerCase();
        boolean extOk = lower.endsWith(".pdf") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".tif") || lower.endsWith(".tiff");

        if (contentType == null) {
            return extOk;
        }

        String ct = contentType.toLowerCase();
        boolean mimeOk = ct.equals("application/pdf") || ct.equals("image/png") || ct.equals("image/jpeg")
                || ct.equals("image/jpg") || ct.equals("image/webp") || ct.equals("image/bmp")
                || ct.equals("image/tiff") || ct.equals("application/octet-stream");

        return extOk || mimeOk;
    }
}
