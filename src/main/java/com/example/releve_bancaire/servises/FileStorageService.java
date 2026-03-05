package com.example.releve_bancaire.servises;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${invoice.upload.dir}")
    private String baseDir;

    public String store(MultipartFile file) {
        try {
            LocalDate now = LocalDate.now();

            Path uploadPath = Paths.get(
                    baseDir,
                    "invoices",
                    String.valueOf(now.getYear()),
                    String.format("%02d", now.getMonthValue()));

            Files.createDirectories(uploadPath);

            String uniqueName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path target = uploadPath.resolve(uniqueName);

            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            // ON RETOURNE LE CHEMIN COMPLET
            return target.toString();

        } catch (Exception e) {
            throw new RuntimeException("Erreur stockage fichier", e);
        }
    }

    public Path load(String fullPath) {
        return Paths.get(fullPath);
    }

    public Path getBaseDirForFiles() {
        return Paths.get(baseDir, "invoices");
    }

}
