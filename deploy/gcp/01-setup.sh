#!/usr/bin/env bash
# One-time setup: APIs, Artifact Registry, Cloud SQL (Postgres 16), generated secrets (DB password, API keys,
# AES-256 data key, HMAC fingerprint key) and the Cloud Run service account. Safe to re-run.
# Requires billing to be enabled on the project.
source "$(dirname "$0")/common.sh"

log "Enabling APIs in $PROJECT_ID"
gcloud services enable run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com \
  cloudbuild.googleapis.com secretmanager.googleapis.com cloudscheduler.googleapis.com iam.googleapis.com \
  --project "$PROJECT_ID"

log "Artifact Registry repository $REPO"
for attempt in 1 2 3 4 5; do   # IAM on a brand-new project can take a minute to propagate
  gcloud artifacts repositories describe "$REPO" --location "$REGION" --project "$PROJECT_ID" >/dev/null 2>&1 && break
  gcloud artifacts repositories create "$REPO" --repository-format docker --location "$REGION" --project "$PROJECT_ID" && break
  echo "  retrying in 20s..."; sleep 20
done

log "Cloud SQL instance $SQL_INSTANCE ($SQL_TIER) - 5 to 10 minutes the first time"
if ! gcloud sql instances describe "$SQL_INSTANCE" --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud sql instances create "$SQL_INSTANCE" --database-version POSTGRES_16 --edition ENTERPRISE \
    --tier "$SQL_TIER" --region "$REGION" --storage-size 10 --storage-auto-increase \
    --backup-start-time 08:00 --enable-point-in-time-recovery --project "$PROJECT_ID" || true
fi
until [ "$(gcloud sql instances describe "$SQL_INSTANCE" --project "$PROJECT_ID" --format='value(state)')" = "RUNNABLE" ]; do
  echo "  waiting for Cloud SQL..."; sleep 15
done
gcloud sql databases describe "$DB_NAME" --instance "$SQL_INSTANCE" --project "$PROJECT_ID" >/dev/null 2>&1 \
  || gcloud sql databases create "$DB_NAME" --instance "$SQL_INSTANCE" --project "$PROJECT_ID"

log "Generated secrets (never printed)"
openssl rand -base64 24 | tr -d '/+=\n' | new_secret paysettle-db-password
openssl rand -hex 24 | tr -d '\n' | new_secret paysettle-operator-api-key
openssl rand -hex 24 | tr -d '\n' | new_secret paysettle-analyst-api-key
openssl rand -base64 32 | tr -d '\n' | new_secret paysettle-data-key          # AES-256-GCM for account numbers
openssl rand -base64 32 | tr -d '\n' | new_secret paysettle-fingerprint-key   # HMAC-SHA256 for duplicate matching
PASSWORD=$(gcloud secrets versions access latest --secret paysettle-db-password --project "$PROJECT_ID")
if gcloud sql users list --instance "$SQL_INSTANCE" --project "$PROJECT_ID" --format='value(name)' | grep -qx "$DB_USER"; then
  gcloud sql users set-password "$DB_USER" --instance "$SQL_INSTANCE" --password "$PASSWORD" --project "$PROJECT_ID"
else
  gcloud sql users create "$DB_USER" --instance "$SQL_INSTANCE" --password "$PASSWORD" --project "$PROJECT_ID"
fi

log "Service account for Cloud Run (least privilege)"
gcloud iam service-accounts describe "$RUN_SA" --project "$PROJECT_ID" >/dev/null 2>&1 \
  || gcloud iam service-accounts create paysettle-run --display-name "PaySettle (Cloud Run)" --project "$PROJECT_ID"
sleep 5
for role in roles/cloudsql.client roles/logging.logWriter roles/monitoring.metricWriter; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$RUN_SA" --role "$role" \
    --condition None --quiet >/dev/null
done
for s in $SECRETS_LIST; do
  gcloud secrets add-iam-policy-binding "$s" --member "serviceAccount:$RUN_SA" \
    --role roles/secretmanager.secretAccessor --project "$PROJECT_ID" >/dev/null
done

log "Cloud Build permissions"
BUILD_SA="$PROJECT_NUMBER-compute@developer.gserviceaccount.com"
for role in roles/artifactregistry.writer roles/logging.logWriter roles/storage.objectViewer; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$BUILD_SA" --role "$role" \
    --condition None --quiet >/dev/null 2>&1 || true
done

log "Setup complete. Next: ./deploy/gcp/02-build.sh"
