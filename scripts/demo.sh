#!/usr/bin/env bash
# scripts/demo.sh
#
# The whole project in one run: show each checkpoint doing its job, then
# show the dashboard reporting what happened.
#
# Prerequisites (this script checks and tells you which are missing):
#   - LocalStack running        docker compose up -d
#   - Minikube + Kyverno        minikube start && helm install kyverno ...
#   - Dashboard running         cd dashboard && mvn spring-boot:run
#   - gh authenticated          gh auth login

set -uo pipefail

ENDPOINT="http://localhost:4566"
DASHBOARD="${DASHBOARD_URL:-http://localhost:8090}"
BUCKET="northbound-analytics-reports"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

heading() { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
note()    { printf '    %s\n' "$1"; }

# ---------------------------------------------------------------------
heading "0. Preflight"

missing=0
if ! curl -sf "$ENDPOINT/_localstack/health" >/dev/null 2>&1; then
  note "MISSING: LocalStack is not responding on $ENDPOINT (docker compose up -d)"
  missing=1
else
  note "LocalStack: up"
fi

if ! kubectl get clusterpolicy >/dev/null 2>&1; then
  note "MISSING: Kyverno ClusterPolicies unreachable (is minikube running?)"
  missing=1
else
  note "Kyverno policies: $(kubectl get clusterpolicy --no-headers 2>/dev/null | wc -l | tr -d ' ') loaded"
fi

if ! curl -sf "$DASHBOARD/" >/dev/null 2>&1; then
  note "MISSING: dashboard not responding on $DASHBOARD (cd dashboard && mvn spring-boot:run)"
  missing=1
else
  note "Dashboard: up"
fi

if [ "$missing" -ne 0 ]; then
  printf '\nStopping: bring the missing pieces up first.\n'
  exit 1
fi

# ---------------------------------------------------------------------
heading "1. Pre-deploy scan gate — Checkov and Trivy (CI)"
note "These run in GitHub Actions on every push. Latest results:"
gh run list --workflow security-scan.yml --limit 1 \
  --json conclusion,displayTitle,url \
  -q '.[] | "    conclusion: \(.conclusion)\n    \(.url)"' 2>/dev/null \
  || note "(gh unavailable — skipping)"
note ""
note "A 'failure' here is the gate working: the seeded flaws are still present."

# ---------------------------------------------------------------------
heading "2a. Deploy-time admission — OPA/conftest against the terraform plan"
# Always plan fresh. Reusing a cached plan file silently reports the
# posture as it was when that file was written — an earlier version of
# this script did exactly that and showed the S3 flaws as still open
# days after they had been fixed.
note "Generating a fresh terraform plan..."
(cd "$ROOT/infra/environments/dev" \
  && terraform plan -out=/tmp/demo.tfplan >/dev/null 2>&1 \
  && terraform show -json /tmp/demo.tfplan > /tmp/demo-plan.json 2>/dev/null)
conftest test --policy "$ROOT/policies/opa" /tmp/demo-plan.json 2>&1 | tail -12
note ""
note "Non-zero exit = the plan would be refused."

# ---------------------------------------------------------------------
heading "2b. Deploy-time admission — Kyverno against the Kubernetes manifest"
note "Attempting to deploy the app as-is (it carries two seeded flaws):"
kubectl apply -f "$ROOT/k8s/base/deployment.yaml" 2>&1 | sed 's/^/    /' | head -14
note ""
note "Rejected at the API server, before any pod was created."

# ---------------------------------------------------------------------
heading "3. Post-deploy detection and automated remediation"
note "Simulating a developer bypassing the pipeline entirely with a direct AWS CLI call."
aws --endpoint-url="$ENDPOINT" s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false" 2>/dev/null
note "Bucket is now public:"
aws --endpoint-url="$ENDPOINT" s3api get-public-access-block --bucket "$BUCKET" \
  --query 'PublicAccessBlockConfiguration' --output json 2>/dev/null | sed 's/^/    /'

note ""
note "Publishing the Config compliance event and waiting for the Lambda..."
"$ROOT/scripts/inject-drift.sh" 2>&1 | grep -E "remediated|NOT remediated" | sed 's/^/    /'

note "Bucket after automated remediation:"
aws --endpoint-url="$ENDPOINT" s3api get-public-access-block --bucket "$BUCKET" \
  --query 'PublicAccessBlockConfiguration' --output json 2>/dev/null | sed 's/^/    /'

# ---------------------------------------------------------------------
heading "4. Reporting — pull everything into the dashboard"
"$ROOT/scripts/sync-findings.py" 2>&1 | sed 's/^/    /'

heading "Done"
note "Open $DASHBOARD to see the resulting posture."
