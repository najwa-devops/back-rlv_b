package com.example.releve_bancaire.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountSeedMigration implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        List<AccountRow> accounts = List.of(
                new AccountRow("111000000", "Capital social", 1),
                new AccountRow("114000000", "Reserves", 1),
                new AccountRow("115000000", "Report a nouveau", 1),
                new AccountRow("116000000", "Resultat de l'exercice", 1),
                new AccountRow("151000000", "Provisions pour risques", 1),
                new AccountRow("164000000", "Emprunts aupres des etablissements de credit", 1),

                new AccountRow("211000000", "Terrains", 2),
                new AccountRow("213000000", "Constructions", 2),
                new AccountRow("215000000", "Installations techniques", 2),
                new AccountRow("218000000", "Autres immobilisations corporelles", 2),
                new AccountRow("232000000", "Immobilisations en cours", 2),
                new AccountRow("281000000", "Amortissements des immobilisations", 2),

                new AccountRow("311000000", "Marchandises", 3),
                new AccountRow("321000000", "Matieres premieres", 3),
                new AccountRow("341000000", "Produits finis", 3),
                new AccountRow("391000000", "Depreciations des stocks", 3),

                new AccountRow("441000000", "Fournisseurs", 4),
                new AccountRow("442000000", "Fournisseurs effets a payer", 4),
                new AccountRow("441100000", "Fournisseurs", 4),
                new AccountRow("441700000", "Fournisseurs retenues de garantie", 4),
                new AccountRow("342000000", "Clients", 4),
                new AccountRow("343000000", "Clients effets a recevoir", 4),
                new AccountRow("345000000", "Etat - TVA", 4),
                new AccountRow("341100000", "Organismes sociaux", 4),
                new AccountRow("444000000", "Personnel - Remunerations dues", 4),
                new AccountRow("471000000", "Comptes d'attente", 4),

                new AccountRow("511000000", "Valeurs a encaisser", 5),
                new AccountRow("514000000", "Banques", 5),
                new AccountRow("516000000", "Caisse", 5),

                new AccountRow("611000000", "Achats revendus", 6),
                new AccountRow("612000000", "Achats consommes", 6),
                new AccountRow("613000000", "Locations et charges locatives", 6),
                new AccountRow("614000000", "Charges externes", 6),
                new AccountRow("616000000", "Missions et deplacements", 6),
                new AccountRow("617000000", "Personnel exterieur", 6),
                new AccountRow("618000000", "Autres charges externes", 6),
                new AccountRow("621000000", "Personnel", 6),
                new AccountRow("622000000", "Remunerations intermediaires", 6),
                new AccountRow("625000000", "Deplacements, missions et receptions", 6),
                new AccountRow("626000000", "Frais postaux et telecommunications", 6),
                new AccountRow("627000000", "Services bancaires", 6),
                new AccountRow("631000000", "Impots et taxes", 6),
                new AccountRow("661000000", "Charges d'interets", 6),

                new AccountRow("711000000", "Ventes de marchandises", 7),
                new AccountRow("712000000", "Ventes de produits finis", 7),
                new AccountRow("713000000", "Production immobilisee", 7),
                new AccountRow("714000000", "Prestations de services", 7),
                new AccountRow("719000000", "Rabais accordes", 7),
                new AccountRow("731000000", "Produits financiers", 7),
                new AccountRow("741000000", "Subventions d'exploitation", 7)
        );

        String sql = """
                INSERT INTO accounts (code, libelle, classe, active, created_at, updated_at)
                VALUES (?, ?, ?, true, NOW(), NOW())
                ON DUPLICATE KEY UPDATE
                    libelle = VALUES(libelle),
                    classe = VALUES(classe),
                    active = true,
                    updated_at = NOW()
                """;

        int updated = 0;
        for (AccountRow row : accounts) {
            updated += jdbcTemplate.update(sql, row.code(), row.libelle(), row.classe());
        }

        log.info("Seed comptes applique pour {} comptes (rows affected={})", accounts.size(), updated);
    }

    private record AccountRow(String code, String libelle, int classe) {}
}
