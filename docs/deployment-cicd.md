# Robot Agent Deployment and CI/CD

This repository now supports a full Docker-based deployment for CentOS and GitHub Actions pipelines that build and publish each subsystem independently.

## Runtime layout

- `frontend`: Nginx container serving the React build and proxying `/api` and `/ws/robot`.
- `java-backend`: Spring Boot container exposing HTTP on `8080` and the Netty gateway on `8091`.
- `python-ai`: FastAPI container exposing `8000` and `/health`.
- Middleware: MySQL, Redis, and pgvector.

## Compose files

- `docker-compose.yml`: local developer stack with source builds.
- `docker-compose.prod.yml`: production stack that only consumes registry images.

## Server bootstrap

1. Install Docker and Docker Compose on the CentOS host.
2. Clone this repository to `/opt/robot-agent` or another directory you prefer.
3. Copy `deploy/.env.example` to `.env` in the application directory and fill in the real values.
4. If your GHCR packages are private, set `GHCR_USERNAME` and `GHCR_TOKEN` in `deploy/.env` or export them before running the scripts.
5. Start middleware first:
   - `sh deploy/scripts/deploy-service.sh middleware`
6. Deploy each app service after the images exist:
   - `sh deploy/scripts/deploy-service.sh python-ai`
   - `sh deploy/scripts/deploy-service.sh java-backend`
   - `sh deploy/scripts/deploy-service.sh frontend`

For a fresh all-in-one bring-up, use:

```sh
sh deploy/scripts/deploy-stack.sh
```

## GitHub Actions

Each workflow only runs when files in its area change:

- `frontend-ci-cd.yml`: `frontend/**`, `docker/nginx/**`
- `java-ci-cd.yml`: `pom.xml`, `java-backend/**`
- `python-ci-cd.yml`: `python-ai/**`
- `middleware-ci-cd.yml`: `docker-compose*.yml`, `deploy/**`

## Required repository settings

Set these repository variables if you want automatic deployment to your CentOS host:

- `CENTOS_HOST`
- `CENTOS_USER`
- `CENTOS_APP_DIR` default: `/opt/robot-agent`
- `CENTOS_SSH_PORT` default: `22`
- `GHCR_USERNAME` optional; defaults to the repository owner in the workflows

Set this secret for SSH access:

- `CENTOS_SSH_KEY`

Optional registry settings for private GHCR packages:

- `GHCR_TOKEN`

## Notes

- Frontend requests go to `/api` and `/ws/robot` from the browser.
- The Java backend reads `ROBOT_PYTHON_BASE_URL` and container DNS names, so inter-service calls stay inside the Docker network.
