package com.example.releve_bancaire.accounting_controller;

import com.example.releve_bancaire.accounting_services.AccountingGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/api/v2/accounting", "/api/accounting"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AccountingGenerationController {

    private final AccountingGenerationService accountingGenerationService;

    @PostMapping("/generate-from-xml")
    public ResponseEntity<?> generateFromXml(
            @RequestParam("xmlFile") MultipartFile xmlFile,
            @RequestParam("nmois") int nmois,
            @RequestParam(name = "year", required = false) Integer year) {
        try {
            if (xmlFile == null || xmlFile.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Le fichier XML est obligatoire."));
            }

            AccountingGenerationService.GenerationResult result =
                    accountingGenerationService.generateFromXml(xmlFile.getBytes(), nmois, year);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Comptabilisation automatique terminee");
            response.put("generatedEntries", result.generatedEntries());
            response.put("nmois", result.nmois());
            response.put("journal", result.journal());
            response.put("lastNumero", result.lastNumero());
            response.put("xmlContainsCredentials", result.xmlContainsCredentials());
            response.put("warning", result.warning());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur generation ecritures comptables depuis XML", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur interne lors de la generation comptable."));
        }
    }

    @PostMapping("/generate-from-xml-url")
    public ResponseEntity<?> generateFromXmlUrl(@RequestBody GenerateFromXmlUrlRequest request) {
        try {
            if (request == null || request.getXmlUrl() == null || request.getXmlUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Le lien XML est obligatoire."));
            }

            AccountingGenerationService.GenerationResult result =
                    accountingGenerationService.generateFromXmlUrl(request.getXmlUrl(), request.getNmois(), request.getYear());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Comptabilisation automatique terminee");
            response.put("generatedEntries", result.generatedEntries());
            response.put("nmois", result.nmois());
            response.put("journal", result.journal());
            response.put("lastNumero", result.lastNumero());
            response.put("xmlContainsCredentials", result.xmlContainsCredentials());
            response.put("warning", result.warning());
            response.put("xmlUrl", request.getXmlUrl());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur generation ecritures comptables depuis lien XML", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur interne lors de la generation comptable."));
        }
    }

    public static class GenerateFromXmlUrlRequest {
        private String xmlUrl;
        private int nmois;
        private Integer year;

        public String getXmlUrl() {
            return xmlUrl;
        }

        public void setXmlUrl(String xmlUrl) {
            this.xmlUrl = xmlUrl;
        }

        public int getNmois() {
            return nmois;
        }

        public void setNmois(int nmois) {
            this.nmois = nmois;
        }

        public Integer getYear() {
            return year;
        }

        public void setYear(Integer year) {
            this.year = year;
        }
    }
}
