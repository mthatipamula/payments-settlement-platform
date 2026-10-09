#!/usr/bin/env bash
# Deploys to Cloud Run and schedules the settlement sweep with Cloud Scheduler.
#
# The service keeps one warm instance with always-on CPU (--no-cpu-throttling): the wire outbox relay and the
# ACH settlement scheduler run in the background, which Cloud Run would otherwise pause between requests.
# ShedLock makes those jobs safe when Cloud Run scales out to more instances.
source "$(dirname "$0")/common.sh"

SECRETS="DB_PASSWORD=paysettle-db-password:latest,OPERATOR_API_KEY=paysettle-operator-api-key:latest"
SECRETS="$SECRETS,ANALYST_API_KEY=paysettle-analyst-api-key:latest,DATA_KEY=paysettle-data-key:latest"
SECRETS="$SECRETS,FINGERPRINT_KEY=paysettle-fingerprint-key:latest"

ENV="DB_URL=jdbc:postgresql:///$DB_NAME?cloudSqlInstance=$SQL_CONNECTION&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
ENV="$ENV##DB_USER=$DB_USER##DB_POOL_SIZE=8##INSTITUTION_NAME=$INSTITUTION_NAME##SIMULATOR_ENABLED=$DEMO_MODE"
if [ -n "$JWT_ISSUER_URI" ]; then ENV="$ENV##SECURITY_MODE=jwt##JWT_ISSUER_URI=$JWT_ISSUER_URI"; else ENV="$ENV##SECURITY_MODE=api-key"; fi

log "Deploying $SERVICE (demo mode: $DEMO_MODE)"
gcloud run deploy "$SERVICE" --project "$PROJECT_ID" --region "$REGION" --image "$IMAGE" \
  --service-account "$RUN_SA" --allow-unauthenticated --port 8080 \
  --memory 1Gi --cpu 1 --cpu-boost --no-cpu-throttling --min-instances 1 --max-instances 3 --concurrency 40 \
  --timeout 300 --set-env-vars "^##^$ENV" --set-secrets "$SECRETS" --quiet

log "Cloud Scheduler: settlement sweep every 5 minutes on business days (backstop for the in-app scheduler)"
KEY=$(gcloud secrets versions access latest --secret paysettle-operator-api-key --project "$PROJECT_ID")
ARGS=(--project "$PROJECT_ID" --location "$REGION" --schedule "*/5 * * * 1-5" --time-zone "America/New_York"
      --uri "$APP_URL/api/v1/ach/settlement/sweep" --http-method POST --attempt-deadline 300s)
if gcloud scheduler jobs describe paysettle-sweep --project "$PROJECT_ID" --location "$REGION" >/dev/null 2>&1; then
  gcloud scheduler jobs update http paysettle-sweep "${ARGS[@]}" --update-headers "X-API-Key=$KEY" --quiet >/dev/null
else
  gcloud scheduler jobs create http paysettle-sweep "${ARGS[@]}" --headers "X-API-Key=$KEY" --quiet >/dev/null
fi

log "Deployed: $APP_URL"
cat <<MSG
  Console:     $APP_URL  (sign in with an API key; copy one with ./deploy/gcp/api-key.sh operator|analyst)
  Health:      $APP_URL/actuator/health
  Status:      ./deploy/gcp/status.sh
MSG
