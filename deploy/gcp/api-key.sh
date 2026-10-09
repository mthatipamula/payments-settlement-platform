#!/usr/bin/env bash
# Copies the operator or analyst API key to the clipboard (macOS) without printing it.
source "$(dirname "$0")/common.sh"
ROLE=${1:-operator}
case "$ROLE" in operator|analyst) ;; *) echo "usage: $0 operator|analyst"; exit 1 ;; esac
gcloud secrets versions access latest --secret "paysettle-$ROLE-api-key" --project "$PROJECT_ID" | tr -d '\n' | pbcopy
echo "The $ROLE API key is on the clipboard."
