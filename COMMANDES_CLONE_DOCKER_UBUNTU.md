# Installation Complete (Ubuntu) - Clone GitHub -> Docker -> Navigateur

## 1) Installer Docker + Docker Compose (plugin officiel)

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
newgrp docker

docker --version
docker compose version
```

## 2) Cloner les 2 dépôts (même arborescence)

```bash
mkdir -p ~/Documents/page-relevee-bancaire/backend
cd ~/Documents/page-relevee-bancaire

git clone https://github.com/najwa-devops/back-rlv_b.git backend/releve_bancaire
git clone https://github.com/najwa-devops/front-rlv_b.git frontend
```

## 3) Se placer sur les commits exacts demandés

```bash
cd ~/Documents/page-relevee-bancaire/backend/releve_bancaire
git checkout 556e975

cd ~/Documents/page-relevee-bancaire/frontend
git checkout a28d2be
```

## 4) Revenir dans le backend et démarrer la stack Docker

```bash
cd ~/Documents/page-relevee-bancaire/backend/releve_bancaire

# Optionnel: si ce fichier n'existe pas, le créer pour fixer le port frontend hôte
grep -q '^FRONTEND_HOST_PORT=' .env 2>/dev/null || echo 'FRONTEND_HOST_PORT=3022' >> .env

docker compose down --remove-orphans
docker compose up -d --build
docker compose ps
```

## 5) Vérifier que tout est UP

```bash
curl -fsS http://localhost:8096/actuator/health
curl -I http://localhost:3022/login
```

Tu dois voir:
- backend: `"status":"UP"`
- frontend: `HTTP/1.1 200 OK`

## 6) Ouvrir dans le navigateur

- Frontend: `http://localhost:3022/login`
- Backend health: `http://localhost:8096/actuator/health`

Identifiants par défaut (seed):
- Email: `superadmin@invoice.local`
- Mot de passe: `Admin@123`

## 7) Si le port 3022 est déjà occupé

```bash
cd ~/Documents/page-relevee-bancaire/backend/releve_bancaire
FRONTEND_HOST_PORT=3023 docker compose up -d --build
docker compose ps
```

Puis ouvrir: `http://localhost:3023/login`

## 8) Commandes utiles

```bash
# Logs
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f mysql

# Redémarrer proprement
docker compose down
docker compose up -d --build
```

