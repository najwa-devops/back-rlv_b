# Explication Complete Du Backend

Ce document explique l'utilite de chaque dossier/fichier du backend, et ce qu'il fait reellement dans le systeme.

## 1) Vue D'ensemble

Le backend fait principalement 6 choses:

1. Authentification et autorisation des utilisateurs (JWT, roles).
2. Upload des releves bancaires.
3. Stockage des releves en base de donnees (BLOB, pas de stockage disque permanent).
4. OCR + extraction des metadonnees et des transactions.
5. Validation comptable (soldes, continuite, qualite).
6. API de correction/validation/comptabilisation + statistiques.

## 2) Arborescence Principale

Sous `src/main/java/com/example/releve_bancaire`:

- `auth/` : securite et login.
- `banking_controller/` : endpoints API metier.
- `banking_services/` : logique metier principale.
- `banking_entity/` : entites JPA (tables DB).
- `banking_repository/` : acces DB (Spring Data JPA).
- `banking_dto/` : objets de transport API.
- `banking_mapper/` : conversion entites <-> DTO.
- `config/` : configuration applicative et migrations runtime.
- `account_tier/` + `repository/AccountDao.java` : plan comptable (table `accounts`).

## 3) Fichiers Racine Applicative

### `ReleveBancaireApplication.java`
- Point d'entree Spring Boot.
- Demarre toute l'application (API, securite, services, migrations).

## 4) Module Comptable (Plan De Comptes)

### `account_tier/Account.java`
- Entite JPA de la table `accounts`.
- Contient: code, libelle, classe, actif, dates, etc.
- Sert de referentiel pour les selecteurs de comptes cote front.

### `repository/AccountDao.java`
- Repository JPA pour lire/rechercher les comptes.
- Exemples: comptes actifs, recherche par code/libelle/classe.

## 5) Module Auth (`auth/*`)

### `auth/controller/AuthController.java`
- Endpoints login + infos utilisateur courant.

### `auth/entity/AppUser.java`
- Entite utilisateur (`app_users`).

### `auth/entity/AppRole.java`
- Enum des roles (SUPER_ADMIN, COMPTABLE, etc.).

### `auth/repository/AppUserRepository.java`
- Acces DB utilisateurs.

### `auth/security/JwtService.java`
- Generation/validation de tokens JWT.

### `auth/security/JwtAuthenticationFilter.java`
- Filtre HTTP qui lit le token et authentifie la requete.

### `auth/security/AppUserDetailsService.java`
- Charge les users pour Spring Security.

### `auth/security/AppUserPrincipal.java`
- Adaptateur `AppUser` vers `UserDetails`.

### `auth/dto/LoginRequest.java`
- Payload login.

### `auth/dto/LoginResponse.java`
- Reponse login (token + infos).

### `auth/dto/CurrentUserResponse.java`
- Reponse endpoint utilisateur courant.

### `auth/config/SuperAdminSeedConfig.java`
- Cree/maintient un super-admin au demarrage selon les variables d'env.

## 6) Controllers Metier (`banking_controller`)

### `AccountingAccountController.java`
- API des comptes comptables (`/api/accounting/accounts`).
- Fournit aussi des options simplifiees pour les selecteurs (code/libelle).

### `BankStatementController.java`
- API centrale des releves:
  - upload
  - process / reprocess
  - lecture liste/detail/final
  - OCR text
  - stats
  - validation / changement statut / comptabilisation
  - suppression
  - endpoint fichier (sert le contenu depuis DB).

### `BankTransactionController.java`
- API des transactions extraites:
  - update unitaire et bulk
  - liaison compte
  - stats par categories
  - reindex.

## 7) DTO (`banking_dto`)

### `BankStatementDTO.java`
- Vue resumee d'un releve.

### `BankStatementDetailDTO.java`
- Vue detaillee (transactions + OCR + validations).

### `BankStatementUploadRequest.java`
- Structure de requete upload (si utilisee selon endpoint).

### `BankTransactionDTO.java`
- Vue transaction.

### `BankTransactionUpdateRequest.java`
- Payload update transaction.

### `BulkUpdateRequest.java`
- Payload update en masse.

### `StatisticsDTO.java`
- DTO de statistiques.

### `ValidationResultDTO.java`
- DTO resultats de validation comptable.

## 8) Entites DB (`banking_entity`)

### `BankStatement.java`
- Entite table `bank_statement`.
- Contient:
  - fichier (meta + `file_data` BLOB + `file_content_type`)
  - metadonnees releve (RIB, banque, periode)
  - soldes/totaux
  - statuts de traitement/validation
  - OCR text nettoye/brut
  - relation vers transactions.

### `BankTransaction.java`
- Entite table `bank_transaction`.
- Une ligne d'operation bancaire extraite.

### `BankTransactionAccountRule.java`
- Entite table des regles d'apprentissage libelle -> compte.

### `BankStatus.java`
- Enum des statuts de releve.

### `BankStatusConverter.java`
- Convertit `BankStatus` vers/depuis la DB.

### `ContinuityStatus.java`
- Etat de continuite entre releves.

## 9) Mapper (`banking_mapper`)

### `BankStatementMapper.java`
- Construit les DTOs de releves a partir des entites.

### `BankTransactionMapper.java`
- Construit les DTOs de transactions.

## 10) Repositories (`banking_repository`)

### `BankStatementRepository.java`
- Requetes DB sur releves:
  - filtres status/periode/rib
  - stats
  - recherches dediees.

### `BankTransactionRepository.java`
- Requetes DB sur transactions.

### `BankTransactionAccountRuleRepository.java`
- Requetes DB sur regles d'apprentissage compte.

## 11) Services Metier (`banking_services`)

### `BankFileStorageService.java`
- Prepare les fichiers uploades pour stockage DB:
  - genere nom technique
  - recupere content-type
  - recupere bytes.

### `BankStatementProcessingService.java`
- Orchestrateur principal:
  1. Met status PROCESSING
  2. Lance extraction OCR/texte
  3. Nettoie le texte
  4. Extrait metadonnees (RIB, periode, etc.)
  5. Extrait transactions
  6. Assigne compte propose/default
  7. Calcule totaux
  8. Valide coherence
  9. Definit statut final
  10. Sauvegarde finale.

### `BankStatementProcessor.java`
- Moteur extraction texte multi-format:
  - PDF texte (PDFBox direct)
  - PDF scanne (OCR)
  - image (OCR)
  - Excel (POI).
- Supporte aussi traitement en memoire depuis `byte[]`.

### `BankStatementValidatorService.java`
- Verifie:
  - coherence des soldes
  - continuite
  - qualite globale.

### `BankTransactionAccountLearningService.java`
- Apprentissage des choix utilisateur:
  - propose un compte selon libelle
  - retourne libelle de compte a afficher.

### `TransactionExtractorService.java`
- Extrait les operations bancaires depuis texte OCR nettoye.

### `TransactionParserFactory.java`
- Choisit le parser adapte selon banque/format.

### `TransactionClassifier.java`
- Classe certaines transactions et calcule des attributs derives.

### `MetadataExtractorService.java`
- Extrait metadonnees du releve: RIB, banque, periode, soldes, totaux.

### `StatementTotalsExtractor.java`
- Extrait et normalise les totaux debits/credits.

### `StatementPeriodExtractor.java`
- Extrait mois/annee.

### `PrimaryRibExtractor.java`
- Extrait le RIB principal du document.

### `BankDetector.java`
- Detecte la banque a partir du contenu texte.

### `BankAliasResolver.java`
- Gere alias/codes de banques + options exposees au front.

### `BankType.java`
- Enum des banques/types supportes.

### `HeaderFooterCleaner.java`
- Nettoie les entetes/pieds OCR pour ameliorer le parsing.

## 12) Sous-module OCR (`banking_services/banking_ocr`)

### `OcrService.java`
- OCR global sur PDF/image/excel avec pipeline.

### `OcrPreProcessor.java`
- Pretraitement image avant OCR (amelioration qualite).

### `OcrCleaningService.java`
- Nettoyage du texte OCR (bruit, espaces, normalisation).

### `FooterExtractionService.java`
- Aide extraction de zones en pied de page.

### `ImageZoneExtractor.java`
- Extraction de zones precises d'une image.

## 13) Sous-module Parseurs Banque (`banking_services/banking_parser`)

### `TransactionParser.java`
- Contrat (interface) des parseurs de transactions.

### `AbstractTransactionParser.java`
- Base commune parseurs.

### `StandardTransactionParser.java`
- Parser generique.

### `AttijariwafaTransactionParser.java`
- Parser specifique Attijariwafa.

### `BcpTransactionParser.java`
- Parser specifique BCP.

### `DateOpDateValTransactionParser.java`
- Parser structure date-op/date-val.

### `LibelleDateValTransactionParser.java`
- Parser structure libelle/date-val.

## 14) Moteur Universel (`banking_services/banking_universal`)

### Fichiers principaux et role

- `UniversalTransactionExtractionEngine.java` : contrat du moteur universel.
- `UniversalTransactionExtractionEngineImpl.java` : implementation principale.
- `BankLayoutProfile.java` : definition d'un profil de mise en page bancaire.
- `BankLayoutProfileRegistry.java` : registre des profils.
- `DefaultBankLayoutProfileRegistry.java` : profils par defaut.
- `TransactionBlock.java` : modele bloc transaction brut.
- `TransactionBlockBuilder.java` : construction de blocs.
- `ScoredTransactionBlockBuilder.java` : blocs + score qualite.
- `NumericClassifier.java` : classification numerique de montants.
- `SmartNumericClassifier.java` : classifieur numerique avance.
- `TransactionConfidenceScorer.java` : contrat scoring confiance.
- `DefaultTransactionConfidenceScorer.java` : scoring par defaut.
- `TransactionExtractionContext.java` : contexte partage d'extraction.
- `BalanceDrivenResolver.java` : resolution guidee par soldes.
- `AccountingBalanceDrivenResolver.java` : variante orientee logique comptable.

## 15) Configuration (`config`)

### `SecurityConfig.java`
- Regles de securite (routes publiques/protegees, JWT filter, roles).

### `TesseractConfig.java`
- Parametrage du moteur OCR Tesseract.

### `Banking_AsyncConfig.java`
- Thread pool async pour traitements lourds.

### `BankStatementSchemaMigration.java`
- Migration runtime de schema DB (index/compatibilite).

### `AccountSeedMigration.java`
- Seed automatique des comptes (`accounts`) au demarrage.

### `BankStatementFileBlobMigration.java`
- Ajoute les colonnes BLOB fichier (`file_data`, `file_content_type`) si absentes.

### `AccountingConfigDropMigration.java`
- Supprime la table `accounting_config` (decision fonctionnelle recente).

## 16) Ressources

### `src/main/resources/application.properties`
- Configuration globale:
  - DB
  - JPA/Hibernate
  - multipart upload
  - OCR/Tesseract
  - CORS
  - JWT
  - port serveur
  - seed super-admin.

## 17) Tests

### `src/test/java/com/example/releve_bancaire/ReleveBancaireApplicationTests.java`
- Test minimal de chargement du contexte Spring.

## 18) Flux Reel D'un Upload (resume)

1. Front envoie fichier a `BankStatementController`.
2. Controller appelle `BankFileStorageService` pour obtenir bytes + metadata.
3. Entite `BankStatement` est creee et sauvegardee en DB (BLOB inclus).
4. `BankStatementProcessingService` lance traitement async.
5. `BankStatementProcessor` extrait texte (PDF/OCR/Excel).
6. Services extraient metadonnees + transactions.
7. Validation + calculs + statut final.
8. Front lit la liste/detail via API.

---

Si besoin, je peux aussi fournir une version UML simplifiee (Controller -> Service -> Repository -> Entity) dans un deuxieme fichier.

