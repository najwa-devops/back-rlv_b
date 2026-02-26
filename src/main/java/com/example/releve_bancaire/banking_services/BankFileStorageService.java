package com.example.releve_bancaire.banking_services;

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
public class BankFileStorageService {

    @Value("${banking.upload.dir:${UPLOAD_BANK_DIR:uploads_banking}}")
    private String bankingBaseDir;

    public String storeBankStatement(MultipartFile file) {
        return storeInBase(file, bankingBaseDir);
    }

    private String storeInBase(MultipartFile file, String rootDir) {
        try {
            LocalDate now = LocalDate.now();

            Path uploadPath = Paths.get(
                    rootDir,
                    "statements",
                    String.valueOf(now.getYear()),
                    String.format("%02d", now.getMonthValue())
            );

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
        return Paths.get(bankingBaseDir, "statements");
    }

    public Path getBankBaseDirForFiles() {
        return Paths.get(bankingBaseDir, "statements");
    }


}
