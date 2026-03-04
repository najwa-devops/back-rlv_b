package com.example.releve_bancaire.banking_services;

import com.example.releve_bancaire.account_tier.Account;
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
public class ExternalComptesCatalogService {

    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NINE_DIGITS = Pattern.compile("^\\d{9}$");

    @Value("${external.comptes.jdbc-url:jdbc:mariadb://172.20.1.11:3306/rlvb_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}")
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

    public List<Account> loadAccounts() {
        String safeTable = sanitizeIdentifierOrThrow(table, "external.comptes.table");
        String safeNumeroColumn = sanitizeIdentifierOrThrow(numeroColumn, "external.comptes.numero-column");
        String safeLibelleColumn = sanitizeIdentifierOrThrow(libelleColumn, "external.comptes.libelle-column");

        String sql = "SELECT " + safeNumeroColumn + " AS numero, " + safeLibelleColumn + " AS libelle " +
                "FROM " + safeTable + " ORDER BY " + safeNumeroColumn + " ASC";

        List<Account> accounts = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            long generatedId = 1L;
            while (rs.next()) {
                String code = normalize(rs.getString("numero"));
                String libelle = normalize(rs.getString("libelle"));
                if (!NINE_DIGITS.matcher(code).matches()) {
                    continue;
                }
                if (libelle.isBlank()) {
                    continue;
                }
                accounts.add(toAccount(generatedId++, code, libelle));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible de lire la table distante Comptes (" + jdbcUrl + ")", e);
        }
        return accounts;
    }

    private Account toAccount(long id, String code, String libelle) {
        Account account = new Account();
        account.setId(id);
        account.setCode(code);
        account.setLibelle(libelle);
        account.setClasse(deriveClasse(code));
        account.setActive(true);
        return account;
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

