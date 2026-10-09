#!/usr/bin/env bash
# Starts Cloud SQL (1-2 minutes), restores one warm Cloud Run instance and resumes the scheduler.
source "$(dirname "$0")/common.sh"
gcloud sql instances patch "$SQL_INSTANCE" --activation-policy=ALWAYS --project "$PROJECT_ID" --quiet
gcloud run services update "$SERVICE" --min-instances 1 --project "$PROJECT_ID" --region "$REGION" --quiet
gcloud scheduler jobs resume paysettle-sweep --project "$PROJECT_ID" --location "$REGION" --quiet || true
