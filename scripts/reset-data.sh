#!/usr/bin/env bash
# Deletes payment data from the LOCAL database (docker compose Postgres). The app can keep running.
#   ./scripts/reset-data.sh          ACH and wires (everything except the sanctions list)
#   ./scripts/reset-data.sh ach      ACH only: files, entries, settlement batches, Spring Batch history
#   ./scripts/reset-data.sh wires    wires only: wires and the settlement outbox
# Fraud alerts, duplicate events and audit history are removed for the chosen rail. Add -y to skip the prompt.
set -euo pipefail
cd "$(dirname "$0")/.."

SCOPE=all
YES=false
for arg in "$@"; do
  case "$arg" in
    ach|wires|all) SCOPE=$arg ;;
    -y|--yes) YES=true ;;
    *) echo "usage: $0 [all|ach|wires] [-y]"; exit 1 ;;
  esac
done

ACH_SQL="
DELETE FROM fraud_alerts     WHERE rail = 'ACH';
DELETE FROM duplicate_events WHERE rail = 'ACH';
DELETE FROM payment_events   WHERE rail = 'ACH';
TRUNCATE ach_entries, ach_batches, ach_settlement_batches, ach_files;
TRUNCATE batch_job_instance CASCADE;   -- Spring Batch job history (executions, steps, contexts)
"
WIRE_SQL="
DELETE FROM fraud_alerts     WHERE rail = 'WIRE';
DELETE FROM duplicate_events WHERE rail = 'WIRE';
DELETE FROM payment_events   WHERE rail = 'WIRE';
TRUNCATE wires, outbox;
"
case "$SCOPE" in
  ach)   SQL=$ACH_SQL ;;
  wires) SQL=$WIRE_SQL ;;
  all)   SQL="$ACH_SQL$WIRE_SQL" ;;
esac

if [ "$YES" != true ]; then
  read -r -p "Delete $SCOPE payment data from the local database? [y/N] " answer
  [ "$answer" = y ] || [ "$answer" = Y ] || { echo "Aborted."; exit 1; }
fi

docker compose exec -T db psql -U paysettle -d paysettle -v ON_ERROR_STOP=1 -q -1 -c "SET client_min_messages = warning; $SQL"
docker compose exec -T db psql -U paysettle -d paysettle -At -c "
  SELECT 'ACH files ' || (SELECT count(*) FROM ach_files) || ', ACH entries ' || (SELECT count(*) FROM ach_entries)
      || ', wires ' || (SELECT count(*) FROM wires) || ', fraud alerts ' || (SELECT count(*) FROM fraud_alerts)
      || ', duplicate events ' || (SELECT count(*) FROM duplicate_events)"
