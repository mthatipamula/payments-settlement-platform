#!/usr/bin/env bash
# Pauses the demo between interviews to save money: Cloud Run goes to zero instances, Cloud SQL stops
# (data is kept; only storage is billed) and the scheduler pauses. Resume with ./deploy/gcp/resume.sh.
source "$(dirname "$0")/common.sh"
gcloud scheduler jobs pause paysettle-sweep --project "$PROJECT_ID" --location "$REGION" --quiet || true
gcloud run services update "$SERVICE" --min-instances 0 --project "$PROJECT_ID" --region "$REGION" --quiet
gcloud sql instances patch "$SQL_INSTANCE" --activation-policy=NEVER --project "$PROJECT_ID" --quiet
