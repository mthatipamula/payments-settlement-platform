#!/usr/bin/env bash
# Generates continuous demo traffic against the local API (the simulator must be enabled, as dev.sh does).
# Each round uploads ACH files (payroll, WEB debits, a mule pattern, re-sent entries and one re-sent file)
# and submits wires (normal, high value, sanctions match, network reject, retries and double submissions).
#   ./scripts/traffic.sh                     a round every 2 minutes until Ctrl+C
#   ROUNDS=30 INTERVAL=120 ./scripts/traffic.sh
# Options: INTERVAL (seconds, at least 60), ROUNDS (0 = forever), ACH_FILES, WIRES, API, API_KEY
set -euo pipefail
API=${API:-http://localhost:8080}
API_KEY=${API_KEY:-local-operator-key}
INTERVAL=${INTERVAL:-120}
ROUNDS=${ROUNDS:-0}
ACH_FILES=${ACH_FILES:-2}
WIRES=${WIRES:-10}

# The simulator stamps files with the creation minute and File ID Modifiers A, B, C...; two rounds in the same
# minute would produce the same NACHA file ID and be rejected as duplicate files.
[ "$INTERVAL" -ge 60 ] || { echo "INTERVAL must be at least 60 seconds"; exit 1; }
curl -sf "$API/actuator/health" >/dev/null || { echo "API not reachable at $API (start it with ./scripts/dev.sh)"; exit 1; }

round=0
while :; do
  round=$((round + 1))
  out=$(curl -s -X POST "$API/api/v1/simulator/run?achFiles=$ACH_FILES&wireCount=$WIRES" -H "X-API-Key: $API_KEY")
  summary=$(printf '%s' "$out" | python3 -c '
import json, sys, collections
try:
    d = json.load(sys.stdin)
except ValueError:
    print("unexpected response"); sys.exit()
if "achFiles" not in d:
    print(d.get("detail") or d); sys.exit()
files = d["achFiles"]
ok = sum(1 for f in files if "fileId" in f)
wires = collections.Counter(w["status"] for w in d["wires"] if not w.get("replay"))
replays = sum(1 for w in d["wires"] if w.get("replay"))
print(f"ACH files accepted {ok}, rejected {len(files) - ok} | wires " + ", ".join(f"{k} {v}" for k, v in sorted(wires.items())) + f" | replays {replays}")
')
  echo "$(date '+%H:%M:%S')  round $round: $summary"
  [ "$ROUNDS" -gt 0 ] && [ "$round" -ge "$ROUNDS" ] && break
  sleep "$INTERVAL"
done
