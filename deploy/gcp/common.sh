# Shared helpers for the deploy scripts.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=config.env
source "$HERE/config.env"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
APP_URL="https://$SERVICE-$PROJECT_NUMBER.$REGION.run.app"   # deterministic Cloud Run URL
IMAGE="$REGION-docker.pkg.dev/$PROJECT_ID/$REPO/$SERVICE:$TAG"
RUN_SA="paysettle-run@$PROJECT_ID.iam.gserviceaccount.com"
SQL_CONNECTION="$PROJECT_ID:$REGION:$SQL_INSTANCE"
SECRETS_LIST="paysettle-db-password paysettle-operator-api-key paysettle-analyst-api-key paysettle-data-key paysettle-fingerprint-key"
log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
secret_exists() { gcloud secrets describe "$1" --project "$PROJECT_ID" >/dev/null 2>&1; }
new_secret() {   # name, generator command output on stdin
  secret_exists "$1" || gcloud secrets create "$1" --data-file=- --replication-policy automatic --project "$PROJECT_ID" >/dev/null
}
