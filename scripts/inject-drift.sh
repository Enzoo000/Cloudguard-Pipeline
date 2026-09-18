#!/usr/bin/env bash
# scripts/inject-drift.sh
#
# Phase 5 drift demo: make the reports bucket public the way a developer
# bypassing the pipeline would (a direct AWS CLI call, no Terraform), then
# publish the AWS Config compliance-change event that would fire on real
# AWS, so the remediation chain runs end to end.
#
# WHY THE EVENT IS PUBLISHED MANUALLY: LocalStack mocks AWS Config — it
# creates recorders, delivery channels and rules, but never records
# resource state, evaluates rules, or emits compliance events (their docs
# say so outright, and describe-compliance-by-config-rule /
# start-config-rules-evaluation both return "has not been implemented").
# So this script stands in for Config's evaluation engine only. Everything
# after the event is genuinely exercised: EventBridge matches the rule
# pattern, routes to the Lambda, and the Lambda calls S3 for real.

set -euo pipefail

ENDPOINT="http://localhost:4566"
BUCKET="northbound-analytics-reports"
RULE="s3-bucket-public-read-prohibited"

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "== 1. Injecting drift: making $BUCKET public (bypassing Terraform) =="
aws --endpoint-url="$ENDPOINT" s3api put-public-access-block \
  --bucket "$BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false"

echo "   current state:"
aws --endpoint-url="$ENDPOINT" s3api get-public-access-block --bucket "$BUCKET" \
  --query 'PublicAccessBlockConfiguration' --output json

echo
echo "== 2. Publishing the Config compliance-change event =="
# Built with python3 rather than a heredoc: the Detail field is a JSON
# string *inside* JSON, and hand-escaping that in bash silently mangles
# the braces.
ENTRIES_FILE="$(mktemp -t drift-entries.XXXXXX.json)"
BUCKET="$BUCKET" RULE="$RULE" REGION="$AWS_DEFAULT_REGION" python3 - "$ENTRIES_FILE" <<'PY'
import json, os, sys

detail = {
    "resourceId": os.environ["BUCKET"],
    "resourceType": "AWS::S3::Bucket",
    "configRuleName": os.environ["RULE"],
    "awsRegion": os.environ["REGION"],
    "newEvaluationResult": {"complianceType": "NON_COMPLIANT"},
}
entries = [{
    "Source": "aws.config",
    "DetailType": "Config Rules Compliance Change",
    "Detail": json.dumps(detail),
}]
with open(sys.argv[1], "w") as f:
    json.dump(entries, f)
PY

aws --endpoint-url="$ENDPOINT" events put-events --entries "file://$ENTRIES_FILE"
rm -f "$ENTRIES_FILE"

echo
echo "== 3. Waiting for the remediation Lambda to restore the baseline =="
for i in $(seq 1 20); do
  STATE=$(aws --endpoint-url="$ENDPOINT" s3api get-public-access-block --bucket "$BUCKET" \
    --query 'PublicAccessBlockConfiguration.BlockPublicAcls' --output text 2>/dev/null || echo "unknown")
  if [ "$STATE" = "True" ]; then
    echo "   remediated after ~$((i * 2))s"
    aws --endpoint-url="$ENDPOINT" s3api get-public-access-block --bucket "$BUCKET" \
      --query 'PublicAccessBlockConfiguration' --output json
    exit 0
  fi
  sleep 2
done

echo "   NOT remediated within 40s - bucket is still public:"
aws --endpoint-url="$ENDPOINT" s3api get-public-access-block --bucket "$BUCKET" \
  --query 'PublicAccessBlockConfiguration' --output json
exit 1
