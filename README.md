# Projet Relevé Bancaire - État Actuel (Mars 2026)

Ce document résume **exactement** l'état actuel du projet, ce qui a été implémenté, ce qui a été validé, et ce qu'il reste à faire.

## 1) Périmètre analysé

- Backend: `/home/najwa/Documents/page-relevee-bancaire/backend/releve_bancaire`
- Frontend: `/home/najwa/Documents/page-relevee-bancaire/frontend`
- Exécution locale via Docker Compose (MariaDB + Backend + Frontend)

## 2) Architecture actuelle

### Backend (Spring Boot)
- API principale de traitement relevés bancaires
- Extraction / gestion transactions
- Workflow de comptabilisation en 2 lignes par transaction
- Génération comptable depuis XML (`wins_os.xml`)
- Écriture dans:
  - `accounting_entry` (journal interne applicatif)
  - `Cptjournal` (table cible métier demandée)

### Frontend (Next.js)
- Liste des relevés
- Détail / modal de relevé
- Actions: valider, comptabiliser, reprocess
- Mise à jour UI optimiste pour statut `COMPTABILISE`

### Base de données (MariaDB)
- DB locale: `rlvb_db`
- User: `nabil1`
- Password: `Nabilkarim1`
- Tables clés ajoutées/configurées:
  - `Comptes`
  - `Cptjournal`
  - `cptjournal_sync_tracker`

## 3) Docker et configuration

### `docker-compose.yml`
Fichier: `docker-compose.yml`

- Service `mariadb`:
  - DB par défaut: `rlvb_db`
  - user/pwd par défaut: `nabil1` / `Nabilkarim1`
  - Port: `3306`
- Service `backend`:
  - URL DB docker: `jdbc:mariadb://mariadb:3306/rlvb_db...`
  - Port: `8096`
- Service `frontend` (profil `frontend`)

### `application.properties`
Fichier: `src/main/resources/application.properties`

- Datasource locale par défaut:
  - `APP_DB_URL=jdbc:mariadb://127.0.0.1:3306/rlvb_db...`
  - user/pwd = `nabil1` / `Nabilkarim1`
- Source comptes externe configurable:
  - `external.comptes.jdbc-url`
  - `external.comptes.username`
  - `external.comptes.password`
  - table/colonnes: `Comptes(numero,libelle)`

## 4) Ce qui a été implémenté (backend)

## 4.1 Comptabilisation: numéro de transaction identique sur les 2 lignes

Fichiers:
- `src/main/java/com/example/releve_bancaire/accounting_services/ComptabilisationWorkflowService.java`
- `src/main/java/com/example/releve_bancaire/accounting_services/AccountingGenerationService.java`

Règle appliquée:
- Une transaction bancaire génère 2 lignes comptables.
- Les 2 lignes gardent le **même numéro de transaction** (`numero` / `Numero`).
- Priorité au `transactionIndex` du relevé quand présent.

## 4.2 Insertion dans `Cptjournal` conforme au format demandé

Fichier: `src/main/resources/schema.sql`

Colonnes actuelles de `Cptjournal`:
- `Numero`
- `ndosjrn`
- `nmois`
- `Mois`
- `ncompt`
- `ecriture`
- `debit`
- `credit`
- `valider`
- `datcompl`
- `dat`
- `annee`
- `mnt_rester`

Règles métier appliquées:
- `valider = '1'`
- `Mois` en français (`janvier`, etc.)
- `Numero`:
  - base = dernier `MAX(Numero)` + 1
  - 2 lignes d'une même transaction => même `Numero`
- `mnt_rester`:
  - si `ncompt` commence par `4411`, `342`, `1481`, `1486`
  - alors `ABS(debit - credit)`
  - sinon `NULL`

## 4.3 Flux XML (`wins_os.xml`) connecté

Fichiers:
- `src/main/java/com/example/releve_bancaire/accounting_services/WinsOsXmlParser.java`
- `src/main/java/com/example/releve_bancaire/accounting_services/AccountingGenerationService.java`
- `src/main/java/com/example/releve_bancaire/accounting_controller/AccountingGenerationController.java`

Format supporté:
```xml
<wins_os.xml>
  <data>
    <compte>...</compte>
    <journal>...</journal>
  </data>
</wins_os.xml>
```

Utilisation actuelle:
- `journal` XML => `ndosjrn`
- `compte` XML => compte de la ligne de contrepartie
- endpoint:
  - `POST /api/accounting/generate-from-xml`
  - `POST /api/accounting/generate-from-xml-url`

## 4.4 Source des comptes (codes 9 chiffres) via table `Comptes`

Fichiers:
- `src/main/java/com/example/releve_bancaire/banking_controller/AccountingAccountController.java`
- `src/main/java/com/example/releve_bancaire/banking_services/ExternalComptesCatalogService.java`
- `src/main/resources/schema.sql`
- `src/main/resources/data.sql`

État:
- Les endpoints comptes lisent la table `Comptes(numero, libelle)`.
- Le fallback local historique est conservé en commentaires (non supprimé).

## 4.5 Synchronisation des anciens relevés déjà comptabilisés

Problème traité:
- Certains relevés étaient déjà `COMPTABILISE` mais `Cptjournal` vide (anciens flux).

Solution:
- Endpoint `PUT /api/v2/bank-statements/{id}/status`:
  - si `status=COMPTABILISE` et écritures déjà présentes:
    - synchronise `Cptjournal` depuis `accounting_entry`
- Anti-duplication:
  - table `cptjournal_sync_tracker`

Fichiers:
- `src/main/java/com/example/releve_bancaire/banking_controller/BankStatementController.java`
- `src/main/java/com/example/releve_bancaire/accounting_services/ComptabilisationWorkflowService.java`
- `src/main/java/com/example/releve_bancaire/accounting_repository/CptjournalSyncTrackerRepository.java`

## 4.6 Migrations techniques ajoutées

- `AccountingEntryNumeroIndexMigration`:
  - rend non-unique l'index `idx_acc_entry_journal_month_numero`
  - permet 2 lignes avec même `numero`
- `CptjournalColumnMigration`:
  - rename colonnes historiques si besoin:
    - `ncompte` -> `ncompt`
    - `datecompl` -> `datcompl`
    - `date` -> `dat`

Fichiers:
- `src/main/java/com/example/releve_bancaire/config/AccountingEntryNumeroIndexMigration.java`
- `src/main/java/com/example/releve_bancaire/config/CptjournalColumnMigration.java`

## 5) Ce qui a été implémenté (frontend)

Fichiers:
- `frontend/app/bank/list/page.tsx`
- `frontend/components/bank-statement-table.tsx`
- `frontend/components/bank-statement-detail-modal.tsx`

Améliorations:
- MàJ optimiste du statut `COMPTABILISE` sans refresh
- Propagation de l'update depuis le modal vers la table puis vers la page
- rollback en cas d'erreur API

## 6) Flux métier actuels (résumé)

### A. Bouton "Comptabiliser" (liste)
- API: `PUT /api/v2/bank-statements/{id}/status` avec `COMPTABILISE`
- Effet:
  - si non comptabilisé: simulate + confirm + insertion `accounting_entry` + `Cptjournal`
  - si déjà comptabilisé avec écritures: sync `Cptjournal` si manquant

### B. Modal "Confirmer Comptabilisation"
- API:
  - `POST /api/comptabilisation/simulate`
  - `POST /api/comptabilisation/confirm`
- Effet:
  - status relevé -> `COMPTABILISE`
  - écritures `accounting_entry`
  - écritures `Cptjournal` (si pas déjà sync)

### C. Génération depuis XML
- API:
  - `POST /api/accounting/generate-from-xml`
- Effet:
  - lit `compte` + `journal` XML
  - écrit `accounting_entry` + `Cptjournal`

## 7) Commandes de run / test

### Backend + DB (Docker)
```bash
cd /home/najwa/Documents/page-relevee-bancaire/backend/releve_bancaire
docker compose down
docker compose build --no-cache backend
docker compose up -d mariadb backend
docker compose ps
docker compose logs -f backend
```

### Frontend (local)
```bash
cd /home/najwa/Documents/page-relevee-bancaire/frontend
npm run dev
```

### Frontend (docker)
```bash
cd /home/najwa/Documents/page-relevee-bancaire/backend/releve_bancaire
docker compose build --no-cache frontend
docker compose up -d frontend
```

### Vérifier `Cptjournal`
```bash
docker exec rlvb-mariadb mariadb -unabil1 -pNabilkarim1 rlvb_db -e "DESCRIBE Cptjournal;"

docker exec rlvb-mariadb mariadb -unabil1 -pNabilkarim1 rlvb_db -e "SELECT Numero, ndosjrn, nmois, Mois, ncompt, ecriture, debit, credit, valider, datcompl, dat, annee, mnt_rester FROM Cptjournal ORDER BY Numero DESC LIMIT 200;"
```

## 8) Reste à faire (priorisé)

1. Unifier définitivement le flux métier unique
- Aujourd'hui il existe encore 3 chemins (status update, simulate/confirm, XML).
- Recommandation: définir un seul flux principal et faire converger les autres dessus.

2. Décider de la stratégie finale XML pour le workflow "confirm"
- Actuellement `simulate/confirm` utilise journal/compte par config.
- Si exigence: imposer `journal+compte` XML aussi dans ce flux, il faut brancher le XML à ce workflow.

3. Finaliser la suppression auth "définitive"
- L'auth est neutralisée côté contrôleur (`/api/auth/login` stub),
- mais des classes/packages sécurité restent présentes.
- Nettoyage complet à faire si objectif: backend totalement sans auth.

4. Tests automatiques
- Ajouter tests d'intégration:
  - insertion 2 lignes / même `Numero`
  - calcul `mnt_rester`

## 9) Nouveau module Centre Monétique (OCR dédié)

Un module séparé a été ajouté côté backend pour traiter les documents de règlement TPE Centre Monétique, sans modifier le pipeline des relevés bancaires.

### Backend API

- `POST /api/v2/centre-monetique/upload`
  - `multipart/form-data`: `file` (obligatoire), `year` (optionnel)
  - support: `pdf`, `png`, `jpg`, `jpeg`, `webp`, `bmp`, `tif`, `tiff`
  - résultat: lignes JSON structurées + ligne `Total`
- `GET /api/v2/centre-monetique?limit=50`
  - historique des batches extraits
- `GET /api/v2/centre-monetique/{id}`
  - détail batch + lignes extraites
- `POST /api/v2/centre-monetique/{id}/reprocess`
  - retraitement à partir du fichier stocké en base
- `GET /api/v2/centre-monetique/{id}/file`
  - consultation du fichier source
- `DELETE /api/v2/centre-monetique/{id}`
  - suppression batch + lignes associées

### Base de données

Tables créées automatiquement par JPA:

- `cm_batch` (fichier, OCR brut, statut, totaux, timestamps)
- `cm_transaction` (lignes extraites `section/date/reference/montant/debit/credit`)

Un composant de migration ajoute les index MariaDB/MySQL:

- `idx_cm_batch_created_at`
- `idx_cm_batch_status`
- `idx_cm_tx_batch_id`
- `idx_cm_tx_batch_row`

### Frontend dédié (dans ce repo backend)

Interface statique disponible à:

- `/centre-monetique/index.html`
- alias: `/centre-monetique.html`

Fonctions:

- upload fichier + extraction
- affichage JSON
- historique des traitements
- ouvrir détail / reprocess / télécharger fichier / supprimer
  - sync `Cptjournal` anciens relevés
  - UI optimistic update (component tests)

5. Passage prod source externe `Comptes`
- Configurer l'IP réelle externe (`172.20.1.11`) et valider connectivité réseau en environnement cible.
- Aujourd'hui Docker local pointe par défaut vers la DB docker locale.

6. Monitoring et idempotence avancée
- Ajouter métriques/logs métier de comptabilisation (par relevé, par batch)
- Éventuellement renforcer la contrainte anti-doublon au niveau SQL métier (selon règle finale).

## 9) Statut global

- Backend: fonctionnel pour les cas demandés, y compris `Cptjournal`.
- Frontend: mise à jour instantanée du statut implémentée sur liste + modal.
- DB: schéma et migrations alignés sur les noms de colonnes demandés.
- Docker: stack de test opérationnelle.
