# Installation Complete Sur Un Autre PC (Sans Docker)

Ce guide lance:
- Backend Spring Boot (port `8096`)
- Frontend Next.js (port `3022`)
- Base MySQL locale (port `3306`)

Il fonctionne sans Docker.

## 1) Préparer l'environnement

## 1.1 Outils obligatoires

Installe:
1. `Git`
2. `Java 17` (JDK)
3. `Node.js 20` + `npm`
4. `MySQL Server 8.x`
5. `Tesseract OCR` (langues `eng` et `fra`)

## 1.2 Vérifier les versions

```bash
git --version
java -version
node -v
npm -v
mysql --version
tesseract --version
```

Attendu:
- Java 17
- Node 20.x

## 2) Cloner les projets (arborescence obligatoire)

Le backend et le frontend doivent être dans le même dossier parent:

```text
page-relevee-bancaire/
  backend/releve_bancaire
  frontend
```

Commandes:

```bash
mkdir -p ~/page-relevee-bancaire/backend
cd ~/page-relevee-bancaire

git clone https://github.com/najwa-devops/back-rlv_b.git backend/releve_bancaire
git clone https://github.com/najwa-devops/front-rlv_b.git frontend
```

## 3) Se placer sur les commits demandés

```bash
cd ~/page-relevee-bancaire/backend/releve_bancaire
git checkout 556e975

cd ~/page-relevee-bancaire/frontend
git checkout a28d2be
```

## 4) Préparer la base MySQL locale

Connecte-toi en root MySQL:

```bash
mysql -u root -p
```

Puis exécute:

```sql
CREATE DATABASE IF NOT EXISTS rlvb_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'rlvb_user'@'localhost' IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';
GRANT ALL PRIVILEGES ON rlvb_db.* TO 'rlvb_user'@'localhost';
FLUSH PRIVILEGES;
```

## 5) Configurer le backend

Fichier: `~/page-relevee-bancaire/backend/releve_bancaire/.env`

Exemple recommandé:

```env
APP_DB_URL=jdbc:mysql://127.0.0.1:3306/rlvb_db?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
APP_DB_USERNAME=rlvb_user
APP_DB_PASSWORD=CHANGE_ME_STRONG_PASSWORD

SERVER_PORT=8096
CORS_ORIGINS=http://localhost:3022,http://127.0.0.1:3022
SHOW_SQL=false

TESSERACT_PATH=/usr/bin/tesseract
TESSERACT_DATAPATH=/usr/share/tesseract-ocr/5/tessdata
TESSERACT_LANGUAGE=eng+fra

SEED_SUPERADMIN_ENABLED=true
SEED_SUPERADMIN_EMAIL=superadmin@invoice.local
SEED_SUPERADMIN_PASSWORD=Admin@123

JWT_SECRET=CHANGE_ME_LONG_RANDOM_SECRET_64_CHARS_MIN
JWT_EXPIRATION_MS=86400000
```

Note Windows (si besoin):
- `TESSERACT_PATH=C:/Program Files/Tesseract-OCR/tesseract.exe`
- `TESSERACT_DATAPATH=C:/Program Files/Tesseract-OCR/tessdata`

## 6) Lancer le backend

```bash
cd ~/page-relevee-bancaire/backend/releve_bancaire
chmod +x mvnw
./mvnw clean spring-boot:run
```

Laisse ce terminal ouvert.

Test backend (nouveau terminal):

```bash
curl http://localhost:8096/actuator/health
```

## 7) Configurer le frontend

Fichier: `~/page-relevee-bancaire/frontend/.env.local`

```env
NEXT_PUBLIC_API_URL=http://localhost:8096
NEXT_PUBLIC_USE_MOCK=false
```

## 8) Lancer le frontend

```bash
cd ~/page-relevee-bancaire/frontend
npm install
npm run dev
```

Le script `dev` est déjà configuré sur le port `3022`.

## 9) Vérifier l'application dans le navigateur

Ouvre:
- `http://localhost:3022/login`

Login seed:
- Email: `superadmin@invoice.local`
- Mot de passe: `Admin@123`

## 10) Vérifications rapides si problème

## 10.1 Vérifier que les ports sont libres

```bash
ss -ltnp | grep -E ':(3022|8096|3306)'
```

## 10.2 Vérifier MySQL

```bash
mysql -u rlvb_user -p -e "USE rlvb_db; SHOW TABLES;"
```

## 10.3 Vérifier API login côté backend

```bash
curl -i -X POST "http://localhost:8096/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"superadmin@invoice.local","password":"Admin@123"}'
```

## 10.4 Si erreur CORS

Vérifie que `CORS_ORIGINS` contient bien:
- `http://localhost:3022`
- `http://127.0.0.1:3022`

Puis redémarre le backend.

## 11) Démarrage quotidien (sans réinstallation)

Ordre recommandé:
1. Démarrer MySQL
2. Démarrer backend
3. Démarrer frontend

Commandes:

```bash
# terminal 1
cd ~/page-relevee-bancaire/backend/releve_bancaire
./mvnw spring-boot:run

# terminal 2
cd ~/page-relevee-bancaire/frontend
npm run dev
```

