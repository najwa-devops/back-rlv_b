# Analyse Complète du Projet (Back + Front)

Document de référence global du projet, de l'entrée utilisateur jusqu'à la persistance en base, avec logique métier, composants, endpoints, état actuel, limites et prochaines étapes.

## 1. Vue d'ensemble

Le projet est une application de traitement de relevés bancaires avec:
- upload de fichiers (PDF/images)
- extraction OCR et parsing des transactions
- validation métier des relevés
- comptabilisation en double écriture (2 lignes par transaction)
- export/écriture comptable dans des tables métier
- interface front de pilotage (liste, détail, validation, comptabilisation)

Stack principale:
- Backend: Spring Boot 3.2.12, Java 17, JPA/Hibernate, MariaDB
- Frontend: Next.js 16, React 19, TypeScript, composants UI Radix/shadcn-like
- OCR: Tess4J + PDFBox + OpenCV
- Exécution locale: Docker Compose

## 2. Arborescence principale

## Backend
Racine: `/home/najwa/Documents/page-relevee-bancaire/backend/releve_bancaire`

Packages majeurs:
- `banking_controller`: APIs relevés/transactions
- `banking_services`: traitement OCR, parsing, orchestration métier
- `banking_entity`, `banking_repository`: modèle et persistance relevés/transactions
- `accounting_controller`: APIs comptabilisation/génération XML
- `accounting_services`: simulation/confirmation comptable + génération XML
- `accounting_entity`, `accounting_repository`: écritures comptables
- `auth/*`: endpoints auth (actuellement neutralisés)
- `config/*`: migrations et configs techniques

## Frontend
Racine: `/home/najwa/Documents/page-relevee-bancaire/frontend`

Zones majeures:
- `app/*`: pages Next.js (liste relevés, upload, dashboard, settings, admin)
- `components/*`: composants métiers
- `components/bank-statement-detail-modal.tsx`: modal détail + simulation/confirm comptabilisation
- `components/bank-statement-table.tsx`: tableau principal des relevés
- `lib/api.ts`: client API central
- `src/*`: providers/contexts complémentaires

## 3. Démarrage applicatif

## Backend
Entrée: `ReleveBancaireApplication`
- package scan: `com.example.releve_bancaire`
- exclusion auto-config sécurité Spring Security web/actuator
- résultat: backend exposé sans chaîne de sécurité Spring standard

## Frontend
Entrée: `app/layout.tsx`
- providers globaux: thème, auth provider, dossier provider, react-query provider
- layout client global via `ClientLayout`

## 4. Modèle métier principal

## Relevé bancaire (`BankStatement`)
Entité centrale avec:
- métadonnées fichier
- statut cycle de vie (`PENDING`, `PROCESSING`, `TREATED`, `READY_TO_VALIDATE`, `VALIDATED`, `COMPTABILISE`, etc.)
- statistiques transactions
- indicateurs validation/continuité
- dates et auteur de validation/comptabilisation

## Transaction bancaire (`BankTransaction`)
- liée à un relevé
- date opération, date valeur, libellé, débit/crédit
- `transactionIndex` = numéro séquentiel dans le relevé
- compte comptable associé (`compte`) côté bancaire

## Écriture comptable interne (`AccountingEntry`)
- 2 lignes par transaction (principale + contrepartie)
- `numero` partagé entre les 2 lignes d'une même transaction
- relation de traçabilité: `sourceStatementId`, `sourceTransactionId`, `batchId`

## Table métier cible `Cptjournal`
Colonnes finales demandées et en place:
- `Numero`, `ndosjrn`, `nmois`, `Mois`, `ncompt`, `ecriture`, `debit`, `credit`, `valider`, `datcompl`, `dat`, `annee`, `mnt_rester`

## Table de catalogue comptes `Comptes`
- structure: `numero`, `libelle`
- utilisée comme source des comptes 9 chiffres affichés au front

## 5. Cycle fonctionnel de bout en bout

## Étape A: Upload et traitement
1. le front envoie le fichier à `/api/v2/bank-statements/upload`
2. backend stocke le fichier et crée le relevé
3. backend déclenche traitement async OCR/parsing
4. transactions alimentées et relevé passe vers statuts de traitement

## Étape B: Validation
1. utilisateur valide le relevé (`/validate`)
2. statut passe à `VALIDATED`

## Étape C: Comptabilisation
Deux chemins existent:
1. workflow simulate/confirm
2. changement direct de statut par endpoint `/status`

Dans les deux cas, objectif final:
- statut relevé = `COMPTABILISE`
- écritures `accounting_entry` présentes
- lignes `Cptjournal` présentes

## Étape D: Génération via XML directeur
API dédiée:
- `/api/accounting/generate-from-xml`
- `/api/accounting/generate-from-xml-url`

XML attendu:
```xml
<wins_os.xml>
  <data>
    <compte>...</compte>
    <journal>...</journal>
  </data>
</wins_os.xml>
```

Usage:
- `journal` XML -> `ndosjrn`
- `compte` XML -> compte contrepartie de la 2e ligne

## 6. Règles métier comptables implémentées

1. double écriture
- chaque transaction génère 2 lignes

2. numéro de transaction identique pour les 2 lignes
- basé sur `transactionIndex` si disponible

3. numérotation `Cptjournal.Numero`
- base = `MAX(Numero)` courant + 1
- progression basée sur numéro de transaction
- 2 lignes d'une transaction partagent le même `Numero`

4. mois/année
- `nmois` numérique
- `Mois` en français (`janvier`, etc.)
- `annee` depuis date transaction

5. `valider`
- forcé à `'1'`

6. `mnt_rester`
- si `ncompt` commence par `4411`, `342`, `1481`, `1486`
- `mnt_rester = ABS(debit - credit)`
- sinon `NULL`

## 7. Endpoints backend (principaux)

## Auth
- `POST /api/auth/login`
- `GET /api/auth/me`

## Relevés bancaires
- `POST /api/v2/bank-statements/upload`
- `POST /api/v2/bank-statements/{id}/process`
- `GET /api/v2/bank-statements`
- `GET /api/v2/bank-statements/{id}`
- `POST /api/v2/bank-statements/{id}/validate`
- `PUT /api/v2/bank-statements/{id}/status`
- `DELETE /api/v2/bank-statements/{id}`

## Transactions bancaires
- `GET /api/v2/bank-transactions/statement/{statementId}`
- `PUT /api/v2/bank-transactions/{id}`
- `PUT /api/v2/bank-transactions/bulk-update`

## Comptabilisation workflow
- `POST /api/comptabilisation/simulate`
- `POST /api/comptabilisation/confirm`

## Génération comptable XML
- `POST /api/accounting/generate-from-xml`
- `POST /api/accounting/generate-from-xml-url`

## Catalogue comptes
- `GET /api/accounting/accounts`
- `GET /api/accounting/accounts/options`

## 8. Frontend: logique d'écrans

## `app/bank/list/page.tsx`
- charge liste des relevés
- actions: valider, comptabiliser, reprocess, supprimer
- filtre par statut
- mise à jour optimiste du statut lors de comptabilisation

## `components/bank-statement-table.tsx`
- tableau principal
- ouvre le modal détail
- relaie les callbacks d'update statut

## `components/bank-statement-detail-modal.tsx`
- détail complet relevé + transactions
- simulate/confirm comptabilisation
- update optimiste local du statut `COMPTABILISE`
- propagation de l'état au parent pour éviter refresh manuel

## `lib/api.ts`
- client central fetch API
- méthodes: upload, validate, update status, simulate/confirm comptabilisation, etc.

## 9. Données et persistance

Schéma SQL initialisé via:
- `src/main/resources/schema.sql`
- `src/main/resources/data.sql`

Contenu important:
- création `Comptes`
- création `Cptjournal` avec colonnes finales demandées
- création `cptjournal_sync_tracker`
- seed de comptes de test dans `Comptes`

## 10. Migrations et garde-fous techniques

1. `AccountingEntryNumeroIndexMigration`
- rend non-unique l'index qui bloquait 2 lignes même numéro

2. `CptjournalColumnMigration`
- rename de colonnes historiques vers noms finaux:
- `ncompte -> ncompt`
- `datecompl -> datcompl`
- `date -> dat`

3. `cptjournal_sync_tracker`
- évite duplication de sync Cptjournal pour un relevé déjà traité

## 11. Cas particuliers déjà traités

1. erreur DB docker (`localhost:3306`)
- corrigé par URL service docker `mariadb:3306`

2. relevés déjà `COMPTABILISE` mais Cptjournal vide
- ajout d'une synchronisation depuis `accounting_entry`

3. statut non mis à jour sans refresh côté UI
- mise à jour optimiste implémentée dans liste + modal

## 12. État actuel de l'authentification

- la sécurité Spring web est désactivée au bootstrap
- `AuthController` renvoie actuellement un utilisateur local no-auth
- il reste du code auth/security dans le projet (hérité)

Conclusion:
- fonctionnement actuel = mode local simplifié
- nettoyage complet possible mais non totalement purgé du code historique

## 13. Dépendances clés

## Backend (`pom.xml`)
- spring-boot-starter-web
- spring-boot-starter-data-jpa
- spring-boot-starter-security (présent mais flux neutralisé en partie)
- maria/mysql drivers
- tess4j, pdfbox, opencv
- jwt libs présentes

## Frontend (`package.json`)
- next 16, react 19
- axios
- react-query
- sonner
- radixes/ui libs
- react-pdf, recharts

## 14. Commandes opérationnelles

## Backend + DB
```bash
cd /home/najwa/Documents/page-relevee-bancaire/backend/releve_bancaire
docker compose down
docker compose build --no-cache backend
docker compose up -d mariadb backend
docker compose ps
```

## Front local
```bash
cd /home/najwa/Documents/page-relevee-bancaire/frontend
npm run dev
```

## Vérification Cptjournal
```bash
docker exec rlvb-mariadb mariadb -unabil1 -pNabilkarim1 rlvb_db -e "DESCRIBE Cptjournal;"
docker exec rlvb-mariadb mariadb -unabil1 -pNabilkarim1 rlvb_db -e "SELECT Numero, ndosjrn, nmois, Mois, ncompt, ecriture, debit, credit, valider, datcompl, dat, annee, mnt_rester FROM Cptjournal ORDER BY Numero DESC LIMIT 200;"
```

## 15. Ce qu'il reste à faire (technique)

1. unification flux comptabilisation
- garder un seul chemin métier de référence
- faire des autres endpoints de simples wrappers

2. stratégie XML unique
- décider si `simulate/confirm` doit aussi être alimenté par XML directeur systématiquement

3. nettoyage auth complet
- retirer classes non utilisées et dépendances inutiles si objectif no-auth final

4. tests automatisés
- tests intégration backend: simulate/confirm, XML, sync tracker, mnt_rester
- tests frontend: changement instantané de statut

5. hardening production
- politiques idempotence métier plus strictes
- observabilité (logs métier structurés, métriques)

6. source comptes externe réelle
- basculer en environnement réseau cible vers l'IP externe (172.20.1.11) si requis
- tester disponibilité/résilience

## 16. Ce qu'il reste à faire (fonctionnel)

1. valider avec métier le mapping exact des comptes (ligne 1 vs ligne 2) selon banque
2. valider la règle exacte de numérotation sur toutes périodes et multi-journaux
3. valider les écrans de confirmation en UX réelle avec utilisateurs finaux
4. formaliser un jeu de données de recette couvrant tous cas limites

## 17. Résumé final

Le projet est une plateforme complète de traitement de relevés bancaires avec OCR, validation, comptabilisation et insertion dans des tables métier (`Cptjournal`). Les demandes majeures de numérotation, structure SQL, source comptes, et propagation statut UI ont été intégrées.

Les points restants sont principalement de consolidation: unification des flux, nettoyage auth complet, test coverage, et cadrage final production.
