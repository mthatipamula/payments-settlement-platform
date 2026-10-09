#!/usr/bin/env bash
# Builds the container (React console + Spring Boot API) in Cloud Build and pushes it to Artifact Registry.
source "$(dirname "$0")/common.sh"
cd "$ROOT"
log "Building $IMAGE"
gcloud builds submit --tag "$IMAGE" --project "$PROJECT_ID" --machine-type e2-highcpu-8
log "Image pushed. Next: ./deploy/gcp/03-deploy.sh"
