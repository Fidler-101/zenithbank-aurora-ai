#!/usr/bin/env bash
# Removes the unused, legacy API Agent (it contained a hard-coded service password).
# Run from the project root. Refuses to delete if anything still references it.
set -euo pipefail
FILE="aurora-ai-service/app/agents/api_agent.py"
[ -f "$FILE" ] || { echo "Already removed: $FILE"; exit 0; }
REFS=$(grep -rn "api_agent" --include=*.py aurora-ai-service | grep -v "^$FILE" || true)
if [ -n "$REFS" ]; then
  echo "STOP - still referenced, not deleting:"; echo "$REFS"; exit 1
fi
rm "$FILE"
echo "Removed $FILE. Rebuild with: docker compose up -d --build aurora-ai-service"
echo "Remember: the old password (ZenithBot#...) was in the file's history on this machine - change that service account password."
