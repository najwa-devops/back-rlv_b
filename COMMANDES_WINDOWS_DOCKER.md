# Installation Complete (Windows) - Clone GitHub -> Docker -> Navigateur

## 1) Prérequis Windows

Installe:
1. Git for Windows
2. Docker Desktop (avec WSL2 activé)

Ouvre **PowerShell** (pas CMD).

## 2) Cloner les 2 projets + commits exacts

```powershell
# Dossier de travail
mkdir C:\work\page-relevee-bancaire -Force
cd C:\work\page-relevee-bancaire

# Clone
git clone https://github.com/najwa-devops/back-rlv_b.git backend\releve_bancaire
git clone https://github.com/najwa-devops/front-rlv_b.git frontend

# Backend commit exact
cd C:\work\page-relevee-bancaire\backend\releve_bancaire
git checkout 556e975

# Frontend commit exact
cd C:\work\page-relevee-bancaire\frontend
git checkout a28d2be
```

## 3) Démarrer Docker Compose

```powershell
cd C:\work\page-relevee-bancaire\backend\releve_bancaire

# Optionnel: forcer le port frontend hôte à 3022
Add-Content .env "`nFRONTEND_HOST_PORT=3022"

docker compose down --remove-orphans
docker compose up -d --build
docker compose ps
```

## 4) Vérifications

```powershell
# Backend
curl http://localhost:8096/actuator/health

# Frontend
curl -I http://localhost:3022/login
```

Résultat attendu:
- backend: `"status":"UP"`
- frontend: `HTTP/1.1 200 OK`

## 5) Ouvrir dans le navigateur

- Frontend: `http://localhost:3022/login`
- Backend health: `http://localhost:8096/actuator/health`

Identifiants seed:
- Email: `superadmin@invoice.local`
- Password: `Admin@123`

## 6) Si le port 3022 est occupé

```powershell
cd C:\work\page-relevee-bancaire\backend\releve_bancaire
$env:FRONTEND_HOST_PORT="3023"
docker compose up -d --build
docker compose ps
```

Puis ouvrir: `http://localhost:3023/login`

## 7) Logs utiles

```powershell
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f mysql
```

## 8) Stop / restart

```powershell
docker compose down
docker compose up -d --build
```

