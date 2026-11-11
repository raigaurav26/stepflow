#!/usr/bin/env bash
# Starts a mix of demo orders so the dashboard at http://localhost:8080/ has something to show.
# Needs the demo workflow: run with STEPFLOW_DEMO_ENABLED=true (docker compose up does this).
set -euo pipefail

BASE="${STEPFLOW_URL:-http://localhost:8080}"
COUNT="${1:-20}"

start() {
  curl -fsS -X POST "$BASE/api/runs" -H 'Content-Type: application/json' -d "$1" > /dev/null
}

for i in $(seq 1 "$COUNT"); do
  case $((i % 5)) in
    0) start "{\"workflowType\":\"order-fulfilment\",\"input\":{\"orderId\":\"D-$i\",\"amount\":$((100 + i * 7)),\"payment\":\"flaky\"}}" ;;
    1) start "{\"workflowType\":\"order-fulfilment\",\"input\":{\"orderId\":\"D-$i\",\"amount\":$((100 + i * 7)),\"payment\":\"declined\"}}" ;;
    2) start "{\"workflowType\":\"order-fulfilment\",\"input\":{\"orderId\":\"D-$i\",\"amount\":$((100 + i * 7)),\"shipping\":\"fail\"}}" ;;
    *) start "{\"workflowType\":\"order-fulfilment\",\"input\":{\"orderId\":\"D-$i\",\"amount\":$((100 + i * 7))}}" ;;
  esac
done

echo "Started $COUNT orders. Open $BASE/ to watch them."
