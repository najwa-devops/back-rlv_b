package com.example.releve_bancaire.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simple controller for comptes (accounts) table.
 * No authentication required.
 */
@RestController
@RequestMapping("/api/comptes")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ComptesController {

    private final JdbcTemplate jdbcTemplate;

    private record TableRef(String schema, String name) {
        String qualified() {
            return "`" + schema + "`.`" + name + "`";
        }
    }

    @GetMapping
    public ResponseEntity<?> listComptes(
            @RequestParam(required = false) Integer classe,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "500") Integer limit) {

        TableRef table = resolveComptesTable();
        if (table == null) {
            return ResponseEntity.ok(Map.of("count", 0, "comptes", List.of()));
        }

        int safeLimit = Math.max(1, Math.min(limit, 2000));

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table.qualified());
        List<Object> params = new ArrayList<>();
        List<String> where = new ArrayList<>();

        if (classe != null && hasColumn(table, "classe")) {
            where.add("classe = ?");
            params.add(classe);
        }
        if (query != null && !query.isBlank()) {
            if (hasColumn(table, "code") && hasColumn(table, "libelle")) {
                where.add("(LOWER(code) LIKE LOWER(?) OR LOWER(libelle) LIKE LOWER(?))");
                params.add("%" + query.trim() + "%");
                params.add("%" + query.trim() + "%");
            } else if (hasColumn(table, "libelle")) {
                where.add("LOWER(libelle) LIKE LOWER(?)");
                params.add("%" + query.trim() + "%");
            }
        }

        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }

        if (hasColumn(table, "code")) {
            sql.append(" ORDER BY code ASC");
        } else if (hasColumn(table, "numero")) {
            sql.append(" ORDER BY numero ASC");
        }

        sql.append(" LIMIT ?");
        params.add(safeLimit);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        List<Map<String, Object>> response = rows.stream().map(this::toResponse).toList();

        return ResponseEntity.ok(Map.of(
                "count", response.size(),
                "comptes", response));
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchComptes(@RequestParam String q) {
        if (q == null || q.length() < 2) {
            return ResponseEntity.ok(Map.of(
                    "count", 0,
                    "comptes", List.of()));
        }
        return listComptes(null, q, 50);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCompte(@PathVariable Long id) {
        TableRef table = resolveComptesTable();
        if (table == null || !hasColumn(table, "id")) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> rows = jdbcTemplate
                .queryForList("SELECT * FROM " + table.qualified() + " WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(rows.get(0)));
    }

    @GetMapping("/by-code/{code}")
    public ResponseEntity<?> getByCode(@PathVariable String code) {
        TableRef table = resolveComptesTable();
        if (table == null) {
            return ResponseEntity.notFound().build();
        }
        String codeColumn = hasColumn(table, "code") ? "code" : (hasColumn(table, "numero") ? "numero" : null);
        if (codeColumn == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> rows = jdbcTemplate
                .queryForList("SELECT * FROM " + table.qualified() + " WHERE " + codeColumn + " = ? LIMIT 1", code);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(rows.get(0)));
    }

    private Map<String, Object> toResponse(Map<String, Object> row) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", pick(row, "id"));
        map.put("code", pick(row, "numero"));
        map.put("numero", pick(row, "numero", "code"));
        map.put("ice", pick(row, "ice"));
        map.put("libelle", pick(row, "libelle", "label", "name"));
        map.put("classe", pick(row, "classe"));
        map.put("tvaRate", pick(row, "tva_rate", "tvaRate", "va", "tva"));
        map.put("va", pick(row, "va", "tva", "tva_rate", "tvaRate"));
        map.put("chrge", pick(row, "chrge", "charge", "charge_account", "chargeAccount"));
        map.put("active", pick(row, "active", "activite"));
        map.put("activite", pick(row, "activite", "active"));
        return map;
    }

    private Object pick(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private TableRef resolveComptesTable() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT table_schema, table_name " +
                            "FROM information_schema.tables " +
                            "WHERE table_name IN ('comptes') " +
                            "ORDER BY (table_schema = DATABASE()) DESC, (table_name = 'comptes') DESC " +
                            "LIMIT 1");
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            Object schema = row.get("table_schema");
            Object name = row.get("table_name");
            if (schema == null || name == null) {
                return null;
            }
            return new TableRef(String.valueOf(schema), String.valueOf(name));
        } catch (DataAccessException ex) {
            return null;
        }
    }

    private boolean hasColumn(TableRef table, String columnName) {
        if (table == null) {
            return false;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                    Integer.class,
                    table.schema(),
                    table.name(),
                    columnName);
            return count != null && count > 0;
        } catch (DataAccessException ex) {
            return false;
        }
    }
}
