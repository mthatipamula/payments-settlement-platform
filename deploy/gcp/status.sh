#!/usr/bin/env bash
# Service URL, health, database state and scheduler job.
source "$(dirname "$0")/common.sh"
echo "URL:       $APP_URL"
echo "Health:    $(curl -s -m 60 "$APP_URL/actuator/health" || echo unreachable)"
echo "Database:  $(gcloud sql instances describe "$SQL_INSTANCE" --project "$PROJECT_ID" --format='value(state,settings.activationPolicy)')"
echo "Service:   $(gcloud run services describe "$SERVICE" --project "$PROJECT_ID" --region "$REGION" --format='value(status.latestReadyRevisionName)')"
echo "Scheduler: $(gcloud scheduler jobs describe paysettle-sweep --project "$PROJECT_ID" --location "$REGION" --format='value(state,schedule)' 2>/dev/null)"
