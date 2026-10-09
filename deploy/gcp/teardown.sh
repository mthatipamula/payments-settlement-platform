#!/usr/bin/env bash
# Deletes everything the deploy scripts created, including the database.
source "$(dirname "$0")/common.sh"
read -r -p "Delete the PaySettle deployment in project $PROJECT_ID (database included)? Type the project id: " answer
[ "$answer" = "$PROJECT_ID" ] || { echo "Aborted."; exit 1; }
gcloud scheduler jobs delete paysettle-sweep --project "$PROJECT_ID" --location "$REGION" --quiet || true
gcloud run services delete "$SERVICE" --project "$PROJECT_ID" --region "$REGION" --quiet || true
gcloud sql instances delete "$SQL_INSTANCE" --project "$PROJECT_ID" --quiet || true
gcloud artifacts repositories delete "$REPO" --location "$REGION" --project "$PROJECT_ID" --quiet || true
for s in $SECRETS_LIST; do gcloud secrets delete "$s" --project "$PROJECT_ID" --quiet || true; done
gcloud iam service-accounts delete "$RUN_SA" --project "$PROJECT_ID" --quiet || true
echo "Done. To remove the whole project instead: gcloud projects delete $PROJECT_ID"
