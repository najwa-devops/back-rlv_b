package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.account_tier.Compte;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ExternalComptesCatalogService {

    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern ACCOUNT_CODE = Pattern.compile("^\\d{4,10}$"); // 4-10 digits instead of exactly 9


    @Value("${external.comptes.jdbc-url:jdbc:mariadb://localhost/scan2?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}")
    @Value("${external.comptes.jdbc-url:jdbc:mysql://172.20.1.11:3306/rlvb_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}")
    private String jdbcUrl;

    @Value("${external.comptes.username:root}")
    private String username;

    @Value("${external.comptes.password:}")
    private String password;

    @Value("${external.comptes.table:Comptes}")
    private String table;

    @Value("${external.comptes.numero-column:numero}")
    private String numeroColumn;

    @Value("${external.comptes.libelle-column:libelle}")
    private String libelleColumn;

    public List<Compte> loadAccounts() {
        log.info("Loading comptes from external database: jdbcUrl={}, table={}, columns=[{}, {}]", 
                jdbcUrl, table, numeroColumn, libelleColumn);
        String safeTable = sanitizeIdentifierOrThrow(table, "external.comptes.table");
        String safeNumeroColumn = sanitizeIdentifierOrThrow(numeroColumn, "external.comptes.numero-column");
        String safeLibelleColumn = sanitizeIdentifierOrThrow(libelleColumn, "external.comptes.libelle-column");

        String sql = "SELECT " + safeNumeroColumn + " AS numero, " + safeLibelleColumn + " AS libelle " +
                "FROM " + safeTable + " ORDER BY " + safeNumeroColumn + " ASC";
        log.info("Executing SQL: {}", sql);

        List<Compte> comptes = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            int totalRows = 0;
            int filteredRows = 0;
            int invalidCodeRows = 0;
            int blankLibelleRows = 0;
            
            while (rs.next()) {
                totalRows++;
                String code = normalize(rs.getString("numero"));
                String libelle = normalize(rs.getString("libelle"));
                
                if (!ACCOUNT_CODE.matcher(code).matches()) {
                    if (invalidCodeRows < 5) {
                        log.debug("Skipping account with invalid code format: '{}' (row {})", code, totalRows);
                    }
                    invalidCodeRows++;
                    filteredRows++;
                    continue;
                }
                if (libelle.isBlank()) {
                    if (blankLibelleRows < 5) {
                        log.debug("Skipping account with blank libelle: '{}' (row {})", code, totalRows);
                    }
                    blankLibelleRows++;
                    filteredRows++;
                    continue;
                }
                comptes.add(toCompte(code, libelle));
            }
            log.info("Loaded {} comptes from external database (total rows: {}, filtered: {}, invalid codes: {}, blank libelles: {})", 
                    comptes.size(), totalRows, filteredRows, invalidCodeRows, blankLibelleRows);
            
            if (comptes.isEmpty() && totalRows > 0) {
                log.warn("All rows were filtered out! Check account code format (should be 4-10 digits) and libelle (should not be blank)");
            }
        } catch (SQLException e) {
            log.error("Failed to load comptes from external database: jdbcUrl={}, error={}", jdbcUrl, e.getMessage(), e);
            throw new IllegalStateException("Impossible de lire la table distante Comptes (" + jdbcUrl + "): " + e.getMessage(), e);
        }
        return comptes;
    }

    private Compte toCompte(String code, String libelle) {
        Compte compte = new Compte();
        compte.setNumero(code);
        compte.setLibelle(libelle);
        compte.setClasse(deriveClasse(code));
        compte.setActive(true);
        return compte;
    }

    private int deriveClasse(String code) {
        if (code == null || code.isBlank()) {
            return 0;
        }
        char first = code.charAt(0);
        if (Character.isDigit(first)) {
            return Character.getNumericValue(first);
        }
        return 0;
    }

    private String sanitizeIdentifierOrThrow(String raw, String propertyName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Configuration vide: " + propertyName);
        }
        String value = raw.trim();
        if (!SIMPLE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException("Configuration invalide pour " + propertyName + ": " + raw);
        }
        return value;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}