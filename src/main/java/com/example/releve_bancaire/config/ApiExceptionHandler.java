package com.example.releve_bancaire.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        return build(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Fichier trop volumineux",
                "La taille du fichier depasse la limite configuree (spring.servlet.multipart.max-file-size).",
                request.getRequestURI());
    }

    @ExceptionHandler({
            MultipartException.class,
            MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            Exception ex,
            HttpServletRequest request) {
        log.warn("Requete invalide [{}]: {}", request.getRequestURI(), ex.getMessage());
        return build(
                HttpStatus.BAD_REQUEST,
                "Requete invalide",
                "Envoyez une requete multipart/form-data avec le champ 'file'.",
                request.getRequestURI());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request) {
        log.error("Erreur metier [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Service indisponible",
                ex.getMessage() != null ? ex.getMessage() : "Une erreur inattendue s'est produite",
                request.getRequestURI());
    }

    private ResponseEntity<Map<String, Object>> build(
            HttpStatus status,
            String error,
            String message,
            String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", path);
        return ResponseEntity.status(status).body(body);
    }
}
